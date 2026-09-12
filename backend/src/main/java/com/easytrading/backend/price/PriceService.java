package com.easytrading.backend.price;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import com.easytrading.backend.price.dto.SignalResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    private final PriceRepository priceRepository;
    private final InstrumentRepository instrumentRepository;
    private final MarketDataClient marketDataClient;
    private final SignalService signalService;

    public PriceService(PriceRepository priceRepository, InstrumentRepository instrumentRepository,
                         MarketDataClient marketDataClient, SignalService signalService) {
        this.priceRepository = priceRepository;
        this.instrumentRepository = instrumentRepository;
        this.marketDataClient = marketDataClient;
        this.signalService = signalService;
    }

    public PriceResult getPrices(String symbol, String rawInterval) {
        Interval interval = Interval.fromCode(rawInterval == null ? "" : rawInterval.trim())
                .orElseThrow(() -> new InvalidIntervalException(
                        "Unknown interval '" + rawInterval + "'. Expected one of: " + Interval.supportedCodes() + "."));

        List<Price> candles = recentCandles(symbol, interval, fetchSize(interval));

        if (needsIngestion(candles, interval)) {
            if (candles.isEmpty() && !instrumentRepository.existsById(symbol)) {
                // same "not found" signal the search endpoint uses
                throw new InstrumentNotFoundException("No instrument found for '" + symbol + "'.");
            }

            // Instrument is real, we just have no candles (MISSING) or stale/thin
            // candles (INSUFFICIENT) for it at this interval -> ingest now.
            List<Candle> fetched = marketDataClient.getCandles(symbol, interval.code(), fetchSize(interval));
            fetched.forEach(candle -> priceRepository.save(toPrice(symbol, interval, candle)));

            // Re-read rather than using `fetched` directly: on the INSUFFICIENT
            // path there can already be older rows in the DB outside whatever range
            // Twelve Data just returned, so the caller should see everything now
            // cached, not just what this one ingest call happened to fetch.
            candles = recentCandles(symbol, interval, fetchSize(interval));
        }

        // The signal sees the warm-up candles; the chart does not (SCRUM-64).
        return new PriceResult(displayWindow(candles, interval), signalFor(candles));
    }

    /**
     * The most recent `limit` candles, oldest-first for charting.
     *
     * The repository query is newest-first so the limit means "the most recent
     * N", then the list is reversed here -- a chart plots left to right.
     */
    private List<Price> recentCandles(String symbol, Interval interval, int limit) {
        List<Price> newestFirst = priceRepository.findBySymbolAndIntervalOrderByDatetimeDesc(
                symbol, interval.code(), PageRequest.of(0, limit));

        List<Price> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return chronological;
    }

    /**
     * The tail the frontend actually draws.
     *
     * We read display + warm-up from the database so the indicator has history
     * behind the first plotted point, then hand only the display window to the
     * caller. Trimming here rather than reading two different windows keeps it
     * to one query.
     */
    private List<Price> displayWindow(List<Price> candles, Interval interval) {
        int window = interval.displayCandles();
        if (candles.size() <= window) {
            return candles;
        }
        return new ArrayList<>(candles.subList(candles.size() - window, candles.size()));
    }

    /**
     * A signal must never be able to break the chart (UC02 extension 5a).
     *
     * The indicator is arithmetic over data we did not write, so a bad series is
     * a real possibility. If anything goes wrong the response degrades to the
     * neutral NONE verdict and the candles still render -- rather than turning a
     * working chart into a 500.
     */
    private SignalResponse signalFor(List<Price> candles) {
        try {
            return signalService.evaluate(candles);
        } catch (RuntimeException ex) {
            return SignalResponse.notEnoughData();
        }
    }

    /**
     * How many candles to ask Twelve Data for: the display window PLUS the
     * signal's warm-up allowance.
     *
     * Fetch size and display size are deliberately different numbers. We store
     * more than we show so the indicator has history to warm up on; the extra
     * candles are read back for the signal and then trimmed off before the
     * response. The warm-up number belongs to the indicator, so it is read from
     * SignalService rather than duplicated here.
     */
    static int fetchSize(Interval interval) {
        return interval.displayCandles() + SignalService.WARMUP_CANDLES;
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
        // now() in UTC, not the JVM default zone: stored intraday datetimes are
        // UTC (TwelveDataMarketDataClient sends timezone=UTC), so comparing them
        // against a machine-local clock would make the threshold drift by whatever
        // offset the container happens to run in.
        boolean stale = newest.isBefore(LocalDateTime.now(ZoneOffset.UTC).minus(interval.stalenessThreshold()));
        return cached.size() < MIN_CANDLES || stale; // INSUFFICIENT
    }

    private Price toPrice(String symbol, Interval interval, Candle candle) {
        return new Price(symbol, interval.code(), candle.datetime(),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }
}
