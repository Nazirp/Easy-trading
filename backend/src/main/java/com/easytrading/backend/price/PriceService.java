package com.easytrading.backend.price;

import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import com.easytrading.backend.price.dto.InstrumentQuoteResponse;
import com.easytrading.backend.price.dto.SignalResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Once the user has selected an instrument from a prior /search, get its price
 * history at the requested interval — from the DB if we have it, otherwise
 * ingest it from Twelve Data on demand and persist it, so the next request for
 * the same symbol+interval is a cache hit.
 *
 * Staleness-aware (see needsIngestion()).
 *
 * How much data comes back is decided HERE, not by the caller. Each
 * chart range owns exactly one interval, so the interval determines the window;
 * /api/getPrice therefore keeps its symbol + interval shape and the frontend
 * never sends a candle count.
 */
@Service
public class PriceService {

    private static final int MIN_CANDLES = 2;

    /** The interval the browse list quotes from -- see browseInstruments(). */
    private static final Interval BROWSE_INTERVAL = Interval.ONE_DAY;

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

    /**
     * Every instrument the app knows about, each with its most recent cached
     * close and the move since the one before it (the
     * search dropdown lists what you can pick instead of waiting for you to
     * guess a symbol).
     *
     * <b>This method never calls Twelve Data.</b> It reads price_candle and
     * stops there -- no ingestion, no staleness check, no getPrices(). That is
     * the whole reason it exists as its own method rather than looping over
     * getPrices(): opening a dropdown must not be able to spend from a budget
     * of 800 requests a day. An instrument with no cached
     * candles simply lists with nulls; see InstrumentQuoteResponse.
     *
     * It lives in the price package, not in instrument, because price already
     * depends on instrument (see the constructor). Putting it the other way
     * round would make the two packages depend on each other.
     *
     * BROWSE_INTERVAL is 1day deliberately: "since yesterday's close" is the
     * change a browse list is understood to mean.
     */
    public List<InstrumentQuoteResponse> browseInstruments() {
        return instrumentRepository.findAll(Sort.by("type", "symbol")).stream()
                .map(this::toQuote)
                .toList();
    }

    private InstrumentQuoteResponse toQuote(Instrument instrument) {
        // Two candles is all this needs: the latest close, and the one it is
        // measured against. Newest-first with a limit of 2, so the query stays
        // an index read however much history the symbol has accumulated.
        List<Price> newestFirst = priceRepository.findBySymbolAndIntervalOrderByDatetimeDesc(
                instrument.getSymbol(), BROWSE_INTERVAL.code(), PageRequest.of(0, 2));

        BigDecimal last = newestFirst.isEmpty() ? null : newestFirst.get(0).getClose();
        BigDecimal change = newestFirst.size() < 2
                ? null
                : percentChange(newestFirst.get(1).getClose(), last);

        return new InstrumentQuoteResponse(
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getType().name().toLowerCase(),
                last,
                change);
    }

    /**
     * (now - previous) / previous, as a percentage rounded to 2dp.
     *
     * Returns null rather than dividing when the previous close is zero or
     * missing. A zero close should not occur for a real instrument, but a
     * divide-by-zero in a dropdown is a 500 on a page that was working.
     */
    static BigDecimal percentChange(BigDecimal previousClose, BigDecimal latestClose) {
        if (previousClose == null || latestClose == null
                || previousClose.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return latestClose.subtract(previousClose)
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 2, RoundingMode.HALF_UP);
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

        // The signal sees the warm-up candles; the chart does not.
        List<Price> display = displayWindow(candles, interval);
        return new PriceResult(display, signalFor(candles, display.size()));
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
     * A signal must never be able to break the chart.
     *
     * The indicator is arithmetic over data we did not write, so a bad series is
     * a real possibility. If anything goes wrong the response degrades to the
     * neutral NONE verdict and the candles still render -- rather than turning a
     * working chart into a 500.
     */
    private SignalResponse signalFor(List<Price> candles, int displayed) {
        try {
            return signalService.evaluate(candles, displayed);
        } catch (RuntimeException ex) {
            return SignalResponse.notEnoughData();
        }
    }

    /**
     * How many candles to ask Twelve Data for: the display window PLUS the
     * signal's warm-up allowance.
     *
     * We store more than we show so the indicator has history to warm up on.
     * The warm-up number belongs to the indicator, so it is read from
     * SignalService rather than duplicated here.
     */
    static int fetchSize(Interval interval) {
        return interval.displayCandles() + SignalService.WARMUP_CANDLES;
    }

    /**
     * Package-private (not private) so PriceServiceTest can call it directly
     * as a pure unit test, no Spring context or database needed.
     *
     * The staleness threshold is 1.5x the candle length rather than exactly one
     * candle -- see Interval.stalenessThreshold() for why. `cached`
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
