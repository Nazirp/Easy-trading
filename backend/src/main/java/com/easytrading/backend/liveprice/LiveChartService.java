package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LiveCandle;
import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Assembles the whole demo-trading chart: Twelve Data's history joined to the
 * live series from the Finnhub trade stream, as one list of 1-minute candles.
 *
 * <h3>Why both halves are candles, and why one minute</h3>
 *
 * One minute is the finest interval any provider offers -- Twelve Data's
 * smallest is {@code 1min}, and TradingView's is the same. At one minute the
 * backfilled half and the live half are the same resolution on the same
 * wall-clock grid, so the chart is one picture rather than two glued together.
 * See {@link LiveCandleService}.
 *
 * <h3>The backfill is fetched ONCE per process, and that is not an optimisation</h3>
 *
 * The page polls {@link #chart} every second. Fetching Twelve Data per request
 * would be 3,600 calls an hour against a budget of <b>800 a day</b> -- the quota
 * would be gone in about fourteen minutes.
 *
 * But the reason it <i>can</i> be held forever is better than "we cached it":
 * the backfill covers the thirty minutes <i>before the first request</i>, and
 * those are closed minutes in the past. They cannot change. As time passes they
 * fall out of the rolling window on their own, and once the live series fills the
 * window the backfill is not consulted at all. One request per server start.
 *
 * A failed fetch is <b>not</b> held -- it is retried, but no more often than
 * {@link #RETRY_AFTER_FAILURE}, so a Twelve Data outage cannot turn a
 * once-a-second poll into a once-a-second retry.
 *
 * <h3>The overlap, and which side wins</h3>
 *
 * The stream starts when the <i>server</i> starts, not when a page opens. Open
 * the page forty minutes later and the live series already covers the whole
 * window, overlapping the backfill completely. The two are merged by minute and
 * <b>the live candle always wins</b>: it is built from every trade in that
 * minute, where the backfilled one is a provider's summary. The backfill only
 * fills minutes the live series has nothing for.
 *
 * <h3>Never persisted</h3>
 *
 * Neither half is written to {@code price_candle} -- see {@link LiveCandleService}.
 */
@Service
public class LiveChartService {

    private static final Logger log = LoggerFactory.getLogger(LiveChartService.class);

    /** The finest interval Twelve Data offers, and the one the live series uses. */
    static final String BACKFILL_INTERVAL = "1min";

    /**
     * Exactly the chart window (UC04 BR8). Not more: anything older than the
     * window would be dropped on arrival.
     */
    static final int BACKFILL_CANDLES = 30;

    /** Don't hammer Twelve Data while it is down; the chart works without history. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(30);

    private final MarketDataClient marketDataClient;
    private final LiveCandleService liveCandleService;

    /** Held once fetched; null until then. Only {@link #chart} uses it. */
    private volatile List<LiveCandle> history;
    private volatile Instant lastFailureAt;

    public LiveChartService(MarketDataClient marketDataClient, LiveCandleService liveCandleService) {
        this.marketDataClient = marketDataClient;
        this.liveCandleService = liveCandleService;
    }

    // ---- the chart -------------------------------------------------------

    /**
     * The whole chart: history and live merged, oldest first, trimmed to the
     * window, forming candle last -- plus the price readout taken from the same
     * assembly, so the number on the page and the last candle cannot disagree.
     *
     * An empty chart is a valid answer (UC04 extension 5a): the server has just
     * started and Twelve Data is unreachable. The page draws nothing and fills in
     * as trades arrive -- a missing past is a degraded chart, never a blocked page.
     *
     * @throws com.easytrading.backend.instrument.InstrumentNotFoundException
     *         the symbol is not the demo instrument
     */
    public LiveChart chart(String symbol) {
        String demoSymbol = DemoInstrument.requireSupported(symbol);

        // ONE read of the live series, used for the merge, for deciding which
        // candles are live, and for staleness. See LiveCandleService.series().
        LiveCandleService.LiveSeries series = liveCandleService.series();
        List<LiveCandle> live = series.candles();
        Instant formingStart =
                series.forming() && !live.isEmpty() ? live.get(live.size() - 1).start() : null;

        Set<Instant> liveStarts = new HashSet<>();
        for (LiveCandle candle : live) {
            liveStarts.add(candle.start());
        }

        // Keyed by bucket start so the merge is by minute rather than by
        // position, and sorted so the result is chronological whatever order the
        // two sources arrived in.
        Map<Instant, LiveCandle> merged = new TreeMap<>();
        for (LiveCandle candle : historyFor(demoSymbol, live)) {
            merged.put(candle.start(), candle);
        }
        // Live last: put() overwrites, which IS the "live always wins" rule.
        for (LiveCandle candle : live) {
            merged.put(candle.start(), candle);
        }

        List<LiveCandle> all = new ArrayList<>(merged.values());
        int window = liveCandleService.windowCandles();
        if (all.size() > window) {
            all = all.subList(all.size() - window, all.size());
        }

        List<ChartCandle> out = new ArrayList<>(all.size());
        for (LiveCandle candle : all) {
            out.add(new ChartCandle(candle,
                    liveStarts.contains(candle.start()),
                    candle.start().equals(formingStart)));
        }

        LiveCandle last = all.isEmpty() ? null : all.get(all.size() - 1);
        return new LiveChart(demoSymbol,
                liveCandleService.candleSeconds(),
                List.copyOf(out),
                last == null ? null : last.close(),
                priceAt(series, last),
                series.stale());
    }

    /**
     * When the displayed price was last true.
     *
     * While trades are flowing that is simply the last trade's timestamp. With no
     * live data at all -- the socket never came up, and the chart is pure
     * backfill -- the newest thing we know is the close of the last backfilled
     * minute, which was true at the <i>end</i> of that minute, not at its start.
     * Saying so keeps the "may be outdated" note honest about how old the number
     * really is instead of understating it by a minute.
     */
    private Instant priceAt(LiveCandleService.LiveSeries series, LiveCandle last) {
        if (series.lastTradeAt() != null) {
            return series.lastTradeAt();
        }
        if (last == null) {
            return null;
        }
        return last.start().plusSeconds(liveCandleService.candleSeconds());
    }

    /**
     * The chart and its readout, as one response.
     *
     * @param candleSeconds stated rather than assumed, so the bucket length stays
     *                      a server decision the frontend reads instead of a
     *                      constant duplicated in two languages
     * @param candles       oldest first; EMPTY is a normal answer, not an error
     * @param price         the last candle's close, or null when there are none
     * @param priceAt       when that price was true
     * @param outdated      true when no trade has arrived recently -- the socket
     *                      has dropped, is reconnecting, or never started
     */
    public record LiveChart(String symbol,
                            int candleSeconds,
                            List<ChartCandle> candles,
                            BigDecimal price,
                            Instant priceAt,
                            boolean outdated) {}

    /**
     * A candle plus where it came from and whether it is finished.
     *
     * {@code live} distinguishes a candle built from every trade on the socket
     * from one Twelve Data summarised for us -- the frontend marks the join
     * between them, because the two providers will not agree to the last decimal
     * and the small step there must not read as market movement (UC04 BR7).
     *
     * {@code forming} is true for at most one candle, the last, and means its
     * high, low and close are still moving. The frontend redraws that one in
     * place instead of appending.
     */
    public record ChartCandle(LiveCandle candle, boolean live, boolean forming) {}

    // ---- the backfill ----------------------------------------------------

    /**
     * The backfill points, OLDEST FIRST, because a chart is plotted left to
     * right. Twelve Data answers newest-first, so the order is fixed here rather
     * than left to the frontend -- one place, not one per consumer.
     *
     * <b>An empty list is a valid answer, not an error</b> (UC04 extension 5a).
     * If Twelve Data is down or has spent the day's 800-request budget, the page
     * should open with an empty chart and a note, and start filling from the
     * present.
     *
     * <p>Used by the legacy {@code /api/getLiveHistory} only, and deliberately
     * <b>not</b> cached: that endpoint is called once per page load, so it has
     * none of the quota problem {@link #chart} has, and a caller asking for
     * "the history" has every right to expect a fresh fetch. Nothing new should
     * call it -- {@code chart} returns history and live together, which is what
     * the page actually needs.
     *
     * @throws com.easytrading.backend.instrument.InstrumentNotFoundException
     *         the symbol is not the demo instrument
     */
    public List<Candle> backfill(String symbol) {
        String demoSymbol = DemoInstrument.requireSupported(symbol);

        List<Candle> candles;
        try {
            candles = marketDataClient.getCandles(demoSymbol, BACKFILL_INTERVAL, BACKFILL_CANDLES);
        } catch (RuntimeException ex) {
            log.warn("Backfill for {} failed; the chart will start empty and fill from live data: {}",
                    demoSymbol, ex.toString());
            return List.of();
        }

        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        List<Candle> chronological = new ArrayList<>(candles);
        // Sorted rather than merely reversed: "oldest first" is then true of
        // whatever order the provider actually sent, not only of the order it
        // documents.
        chronological.sort(Comparator.comparing(Candle::datetime));
        return List.copyOf(chronological);
    }

    /**
     * Skipped entirely once the live series covers the whole window, which is the
     * steady state after half an hour of uptime.
     */
    private List<LiveCandle> historyFor(String symbol, List<LiveCandle> live) {
        if (history != null) {
            return history;
        }
        if (live.size() >= liveCandleService.windowCandles()) {
            return List.of();   // the live series already fills the chart
        }
        if (lastFailureAt != null
                && Duration.between(lastFailureAt, Instant.now()).compareTo(RETRY_AFTER_FAILURE) < 0) {
            return List.of();   // backing off after a failure
        }
        return fetchHistory(symbol);
    }

    private synchronized List<LiveCandle> fetchHistory(String symbol) {
        if (history != null) {
            return history;     // another thread won the race
        }

        List<Candle> candles = backfill(symbol);
        if (candles.isEmpty()) {
            lastFailureAt = Instant.now();
            return List.of();
        }

        List<LiveCandle> converted = new ArrayList<>(candles.size());
        for (Candle candle : candles) {
            if (candle.datetime() == null || candle.close() == null) {
                continue;
            }
            // Twelve Data hands back a zone-less LocalDateTime that IS UTC,
            // because getCandles always sends timezone=UTC (CONTRACTS.md §2).
            // This is the one place that conversion happens; getting it wrong
            // would shift the whole backfilled half of the chart by hours.
            converted.add(new LiveCandle(candle.datetime().toInstant(ZoneOffset.UTC),
                    candle.open(), candle.high(), candle.low(), candle.close()));
        }

        history = List.copyOf(converted);
        lastFailureAt = null;
        log.info("Demo chart backfill held: {} one-minute candles for {}.", history.size(), symbol);
        return history;
    }

    /**
     * Throws away the held backfill so the next {@link #chart} call fetches again.
     *
     * <b>For tests only.</b> The Spring context is shared between test methods, so
     * without this the first test's stubbed backfill would silently become every
     * later test's backfill and a stubbing change would appear to have no effect.
     * Nothing in production calls it -- "once per process" is the intended
     * production behaviour, not an accident this method exists to undo.
     */
    void forgetBackfill() {
        history = null;
        lastFailureAt = null;
    }
}
