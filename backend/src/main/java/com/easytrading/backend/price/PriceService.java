package com.easytrading.backend.price;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
 *
 * How much data comes back is decided HERE, not by the caller (SCRUM-62). Each
 * chart range owns exactly one interval, so the interval determines the window;
 * /api/getPrice therefore keeps its symbol + interval shape and the frontend
 * never sends a candle count.
 */
@Service
public class PriceService {

    // Matches check_price_data()'s own min_candles constant in db/schema.sql.
    private static final int MIN_CANDLES = 2;

    /**
     * Extra candles fetched BEYOND the display window, so a moving average has
     * something to warm up on before the first plotted point.
     *
     * Without this, the first N points of every chart would sit inside the
     * indicator's own warm-up period and the signal would read NONE (or worse,
     * be computed from a partial average). 20 covers the longest period under
     * consideration for the MVP crossover; SCRUM-46 owns the actual indicator
     * and should move this next to it once the periods are fixed.
     */
    private static final int SIGNAL_WARMUP_CANDLES = 20;

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

        List<Price> cached = recentCandles(symbol, interval);
        if (!needsIngestion(cached, interval)) {
            return cached;
        }

        if (cached.isEmpty() && !instrumentRepository.existsById(symbol)) {
            // same "not found" signal the search endpoint uses
            throw new InstrumentNotFoundException("No instrument found for '" + symbol + "'.");
        }

        // Instrument is real, we just have no candles (MISSING) or stale/thin
        // candles (INSUFFICIENT) for it at this interval -> ingest now.
        List<Candle> candles = marketDataClient.getCandles(symbol, interval.code(), fetchSize(interval));
        candles.forEach(candle -> priceRepository.save(toPrice(symbol, interval, candle)));

        // Re-read rather than returning `candles` directly: on the INSUFFICIENT
        // path there can already be older rows in the DB outside whatever range
        // Twelve Data just returned, so the caller should see everything now
        // cached, not just what this one ingest call happened to fetch.
        return recentCandles(symbol, interval);
    }

    /**
     * The interval's display window, oldest-first for charting.
     *
     * The repository query is newest-first so the limit means "the most recent
     * N", then the list is reversed here -- a chart plots left to right.
     */
    private List<Price> recentCandles(String symbol, Interval interval) {
        List<Price> newestFirst = priceRepository.findBySymbolAndIntervalOrderByDatetimeDesc(
                symbol, interval.code(), PageRequest.of(0, interval.displayCandles()));

        List<Price> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return chronological;
    }

    /**
     * How many candles to ask Twelve Data for: the display window PLUS the
     * signal's warm-up allowance.
     *
     * Fetch size and display size are deliberately different numbers. We store
     * more than we show so the indicator has history to warm up on; the extra
     * candles stay in the database and simply fall outside the window
     * recentCandles() returns.
     */
    static int fetchSize(Interval interval) {
        return interval.displayCandles() + SIGNAL_WARMUP_CANDLES;
    }

    /**
     * MISSING/INSUFFICIENT/OK from check_price_data() in db/schema.sql,
     * reimplemented here rather than called live -- see class javadoc.
     * Package-private (not private) so PriceServiceTest can call it directly
     * as a pure unit test, no Spring context or database needed.
     *
     * The staleness threshold is 1.5x the candle length rather than exactly one
     * candle (SCRUM-62) -- see Interval.stalenessThreshold() for why. `cached`
     * is expected oldest-first, as recentCandles() returns it.
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
