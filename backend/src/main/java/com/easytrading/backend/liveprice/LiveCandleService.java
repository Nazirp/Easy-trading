package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LiveCandle;
import com.easytrading.backend.liveprice.dto.LivePrice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The live candle series, aggregated from the Finnhub trade stream.
 *
 * <h3>One minute</h3>
 *
 * <b>One minute is the finest interval any provider offers</b> (Twelve Data's
 * smallest is {@code 1min}), so at one minute the
 * backfilled half of the chart and the live half are the <i>same</i> resolution
 * on the <i>same</i> grid rather than two pictures glued together; and a minute
 * is the interval a trader actually reads. The page still feels live because the
 * forming candle's high, low and close move on every tick.
 *
 * <h3>Why the server owns it</h3>
 *
 * See {@link LiveCandleAggregator}: the browser polls and therefore sees roughly
 * one trade in twenty, so a browser-computed high and low would be systematically
 * too narrow. Aggregating here also means a page reload keeps the chart, and two
 * viewers see identical candles.
 *
 * <h3>Not persisted</h3>
 *
 * A rolling window in memory. {@code price_candle}'s CHECK constraint allows the
 * four chart intervals and not {@code 1min}, deliberately: a 1-minute candle from
 * forty minutes ago is outside this window and nothing else in the application
 * asks for that resolution. The one price that must survive is the price a
 * simulated trade executed at, and that is stored on the trade row.
 */
@Service
public class LiveCandleService {

    /** One minute — the finest interval any provider offers, so both halves of the chart share it. */
    static final Duration CANDLE_LENGTH = Duration.ofMinutes(1);

    /** 30 minutes of them, matching the backfill window. */
    static final int WINDOW_CANDLES = (int) (Duration.ofMinutes(30).toMillis() / CANDLE_LENGTH.toMillis());

    private final LiveCandleAggregator aggregator =
            new LiveCandleAggregator(CANDLE_LENGTH.toMillis(), WINDOW_CANDLES);

    /**
     * How long without a trade before the feed counts as stale. Shared with
     * LivePriceService, because it is the same question asked twice.
     */
    private final Duration maxPriceAge;

    public LiveCandleService(@Value("${liveprice.max-price-age:6s}") Duration maxPriceAge) {
        this.maxPriceAge = maxPriceAge;
    }

    /**
     * Called by {@link FinnhubTradeStream} for <b>every</b> trade, not just the
     * newest in a batch — a high or a low that only one trade touched is exactly
     * what a candle exists to carry.
     *
     * Runs on the socket's callback thread; synchronized against the readers
     * below, which run on HTTP request threads. The work inside is a few
     * comparisons, so the lock is never held long enough to slow delivery.
     */
    public synchronized void acceptStreamedPrice(LivePrice trade) {
        if (trade != null) {
            aggregator.accept(trade.price(), trade.timestamp());
        }
    }

    /**
     * Everything a caller needs about the live series, read <b>under one lock at
     * one instant</b>.
     *
     * This is one method rather than four getters on purpose. Trades arrive about
     * twenty times a second, so between two separate calls a minute can end, a new
     * bucket can open, and the answers stop describing the same moment: a chart
     * assembled from one call and labelled from the next can mark a sealed candle
     * as still forming, or call the feed stale while holding a trade that just
     * arrived.
     *
     * @param candles     the window, oldest first, forming candle last; empty
     *                    before the first trade
     * @param forming     true when the last candle is still being built
     * @param lastTradeAt when the most recent trade arrived, or null if none has
     * @param stale       true when no trade has arrived within {@code maxPriceAge}
     */
    public record LiveSeries(List<LiveCandle> candles,
                             boolean forming,
                             Instant lastTradeAt,
                             boolean stale) {}

    /**
     * The live series as it stands. Makes no upstream call, which matters because
     * the chart is polled once a second and must never trigger a REST request.
     */
    public synchronized LiveSeries series() {
        Instant now = Instant.now();
        // snapshot() first: it seals a bucket whose minute has ended, so the
        // forming flag read after it is the one that matches these candles.
        List<LiveCandle> candles = aggregator.snapshot(now);
        Instant lastTradeAt = aggregator.lastTradeAt();
        boolean stale = lastTradeAt == null
                || Duration.between(lastTradeAt, now).compareTo(maxPriceAge) > 0;
        return new LiveSeries(candles, aggregator.hasFormingCandle(), lastTradeAt, stale);
    }

    /** Candle length in seconds, so the response states it rather than the frontend assuming it. */
    public int candleSeconds() {
        return (int) CANDLE_LENGTH.toSeconds();
    }

    /** How many candles the chart window holds. */
    public int windowCandles() {
        return WINDOW_CANDLES;
    }
}
