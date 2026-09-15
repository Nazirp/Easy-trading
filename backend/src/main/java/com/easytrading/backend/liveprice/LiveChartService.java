package com.easytrading.backend.liveprice;

import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The chart's starting state for demo trading (UC04 step 5, SCRUM-72): roughly
 * the last 30 minutes of BTC/USD, so the page is never a blank rectangle waiting
 * for its first 5-second point.
 *
 * <h3>Why this bypasses PriceService</h3>
 *
 * Every other chart in the application goes through PriceService, which caches
 * candles in `price_candle` and picks the interval from the {@code Interval}
 * enum. This one deliberately does neither, and calls {@link MarketDataClient}
 * directly:
 *
 * <ul>
 *   <li><b>1min is not one of our intervals.</b> {@code Interval} has 2h, 4h,
 *       1day and 1week -- the four chart ranges -- and `price_candle`'s CHECK
 *       constraint allows exactly those. A 1-minute row would be rejected by
 *       the database, and rightly: no range asks for it.</li>
 *   <li><b>These points are display-only and must never be persisted.</b> They
 *       are scenery for the first 30 minutes of a demo, replaced by live points
 *       as they arrive and gone from the window half an hour later. Caching
 *       them would fill the historical cache with a resolution nothing reads,
 *       and re-serving them later would be worse than useless -- 1-minute
 *       candles from an hour ago are not "recent" in any sense the live chart
 *       means.</li>
 * </ul>
 *
 * So: no repository is injected here, which is the strongest way to say that
 * nothing on this path can write to the database.
 */
@Service
public class LiveChartService {

    private static final Logger log = LoggerFactory.getLogger(LiveChartService.class);

    /**
     * The finest interval Twelve Data's free plan offers, and the one that makes
     * a 30-minute window look like a line rather than three steps.
     */
    static final String BACKFILL_INTERVAL = "1min";

    /**
     * 30 one-minute candles = the chart's 30-minute rolling window (UC04 BR8).
     * Not more: anything older than the window would be dropped on arrival.
     */
    static final int BACKFILL_CANDLES = 30;

    private final MarketDataClient marketDataClient;

    public LiveChartService(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    /**
     * The backfill points, OLDEST FIRST, because a chart is plotted left to
     * right. Twelve Data answers newest-first, so the order is fixed here rather
     * than left to the frontend -- one place, not one per consumer.
     *
     * Only the close of each candle is used. The live tail is a line of single
     * prices (UC04 BR3), so a candlestick past joined to a line present would be
     * two different kinds of picture on one axis.
     *
     * <b>An empty list is a valid answer, not an error</b> (UC04 extension 5a).
     * If Twelve Data is down or has spent the day's 800-request budget, the page
     * should open with an empty chart and a note, and start filling from the
     * present -- a missing past is a degraded chart, never a blocked page. The
     * frontend therefore treats "no points" as "history unavailable" and carries
     * straight on to polling.
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
}
