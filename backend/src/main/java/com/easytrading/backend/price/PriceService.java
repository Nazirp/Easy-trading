package com.easytrading.backend.price;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * UC01 step 10: once the user has selected an instrument from a prior /search,
 * get its price history at the requested interval — from the DB if we have it,
 * otherwise ingest it from Twelve Data on demand and persist it, so the next
 * request for the same symbol+interval is a cache hit.
 *
 * Staleness-aware: mirrors the MISSING/INSUFFICIENT/OK distinction that
 * check_price_data() in db/schema.sql models, reimplemented here in Java
 * (see needsIngestion()) rather than called as a live DB function -- keeps
 * the business rule in the business logic layer, testable with a plain unit
 * test and no database, and classifying a cache we've already fetched costs
 * no extra round trip to Postgres.
 */
@Service
public class PriceService {

    // Matches check_price_data()'s own min_candles constant in db/schema.sql.
    private static final int MIN_CANDLES = 2;

    private final PriceRepository priceRepository;
    private final InstrumentRepository instrumentRepository;
    private final MarketDataClient marketDataClient;

    public PriceService(PriceRepository priceRepository, InstrumentRepository instrumentRepository,
                         MarketDataClient marketDataClient) {
        this.priceRepository = priceRepository;
        this.instrumentRepository = instrumentRepository;
        this.marketDataClient = marketDataClient;
    }

    public List<Price> getPrices(String symbol, String rawInterval) {
        Interval interval = Interval.fromCode(rawInterval == null ? "" : rawInterval.trim())
                .orElseThrow(() -> new InvalidIntervalException(
                        "Unknown interval '" + rawInterval + "'. Expected one of: " + Interval.supportedCodes() + "."));

        List<Price> cached = priceRepository.findBySymbolAndIntervalOrderByDatetime(symbol, interval.code());
        if (!needsIngestion(cached, interval)) {
            return cached;
        }

        if (cached.isEmpty() && !instrumentRepository.existsById(symbol)) {
            // same "not found" signal the search endpoint uses
            throw new InstrumentNotFoundException("No instrument found for '" + symbol + "'.");
        }

        // Instrument is real, we just have no candles (MISSING) or stale/thin
        // candles (INSUFFICIENT) for it at this interval -> ingest now.
        List<Candle> candles = marketDataClient.getCandles(symbol, interval.code());
        candles.forEach(candle -> priceRepository.save(toPrice(symbol, interval, candle)));

        // Re-read rather than returning `candles` directly: on the INSUFFICIENT
        // path there can already be older rows in the DB outside whatever range
        // Twelve Data just returned, so the caller should see everything now
        // cached, not just what this one ingest call happened to fetch.
        return priceRepository.findBySymbolAndIntervalOrderByDatetime(symbol, interval.code());
    }

    /**
     * MISSING/INSUFFICIENT/OK from check_price_data() in db/schema.sql,
     * reimplemented here rather than called live -- see class javadoc.
     * Package-private (not private) so PriceServiceTest can call it directly
     * as a pure unit test, no Spring context or database needed.
     *
     * NOTE (carried over from the SQL version): the staleness threshold is
     * exactly one interval, which is strict. Forex and stock markets close on
     * weekends, so on a Sunday the newest 2h candle is legitimately hours old
     * and this reports INSUFFICIENT, triggering an ingestion call that finds
     * nothing new. If that turns into wasted Twelve Data requests against the
     * 800/day cap, widen the thresholds (e.g. 1.5x the interval) or make them
     * market-hours aware.
     */
    boolean needsIngestion(List<Price> cached, Interval interval) {
        if (cached.isEmpty()) {
            return true; // MISSING
        }
        LocalDateTime newest = cached.get(cached.size() - 1).getDatetime(); // already ordered by datetime
        boolean stale = newest.isBefore(LocalDateTime.now().minus(interval.stalenessThreshold()));
        return cached.size() < MIN_CANDLES || stale; // INSUFFICIENT
    }

    private Price toPrice(String symbol, Interval interval, Candle candle) {
        return new Price(symbol, interval.code(), candle.datetime(),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }
}
