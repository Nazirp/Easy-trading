package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LiveCandle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Turns a stream of trades into fixed-length candles.
 *
 * <h3>Why the server does this and the browser does not</h3>
 *
 * The browser polls; the server streams. Finnhub pushes ~20 trades a second, so a
 * 1-minute candle is built from roughly <b>1,200 trades</b> — but a page polling
 * once a second only ever sees about 60 of them. A high and low computed from
 * that sample would be systematically too narrow, because the extremes usually
 * fall in the nineteen ticks the poll missed, and two people watching the same
 * market would see different wicks depending on when their polls landed. Only something
 * that sees every trade can compute a true high and low, and that is the server.
 *
 * <h3>Pure on purpose</h3>
 *
 * No Spring, no network, and no clock of its own — {@code now} is always passed
 * in, so "an hour of silence passed" is a value rather than an hour of waiting.
 *
 * <h3>Rules worth knowing before changing it</h3>
 *
 * <ul>
 * <li><b>Buckets align to the wall clock</b> ({@code floor(epochMillis / bucketMillis)}).
 *       Two viewers must see the same candles at the same boundaries, and at one
 *       minute those boundaries are also exactly Twelve Data's, so the backfilled
 *       half of the chart and the live half share one grid.</li>
 *   <li><b>A bucket with no trades produces no candle</b> — a gap, not an
 *       invention. On a candle chart a missing
 *       candle is unremarkable, and carrying one forward would assert the market
 *       did not move during a minute when the truth is that we were not
 *       listening.</li>
 *   <li><b>A trade for a bucket that has already closed is dropped.</b> Trades can
 *       arrive fractionally out of order, and rewriting a sealed candle would
 *       change history under a chart that has already drawn it.</li>
 * </ul>
 *
 * Not thread-safe by itself — {@link LiveCandleService} owns that.
 */
public class LiveCandleAggregator {

    private final long bucketMillis;
    private final int maxCandles;

    /** Sealed candles, oldest first, bounded by maxCandles. */
    private final Deque<LiveCandle> finished = new ArrayDeque<>();

    /** The bucket currently open, or -1 when none is. */
    private long currentBucket = -1;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;

    /** When the most recent trade arrived, for the staleness check. */
    private Instant lastTradeAt;

    public LiveCandleAggregator(long bucketMillis, int maxCandles) {
        if (bucketMillis <= 0 || maxCandles <= 0) {
            throw new IllegalArgumentException("bucket length and window must both be positive");
        }
        this.bucketMillis = bucketMillis;
        this.maxCandles = maxCandles;
    }

    /** One trade. Called ~20 times a second while the stream is healthy. */
    public void accept(BigDecimal price, Instant at) {
        if (price == null || at == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        long bucket = bucketOf(at);

        if (currentBucket >= 0 && bucket < currentBucket) {
            return;   // already sealed — see the class javadoc
        }

        lastTradeAt = at;

        if (currentBucket < 0) {
            startBucket(bucket, price);
            return;
        }
        if (bucket == currentBucket) {
            if (price.compareTo(high) > 0) high = price;
            if (price.compareTo(low) < 0) low = price;
            close = price;
            return;
        }
        seal();
        startBucket(bucket, price);
    }

    /**
     * The series as it stands, oldest first, with the candle currently forming
     * last. Empty until the first trade arrives.
     *
     * Advances the clock as a side effect: if the open bucket's minute has
     * already ended, it is sealed here, because a candle whose minute is over is
     * finished whether or not another trade has arrived to prove it.
     */
    public List<LiveCandle> snapshot(Instant now) {
        if (currentBucket >= 0 && bucketOf(now) > currentBucket) {
            seal();
        }
        List<LiveCandle> out = new ArrayList<>(finished.size() + 1);
        out.addAll(finished);
        if (currentBucket >= 0) {
            out.add(currentCandle());
        }
        return List.copyOf(out);
    }

    /** When the last trade arrived, or null if none ever has. */
    public Instant lastTradeAt() {
        return lastTradeAt;
    }

    /** True while a candle is still being formed — i.e. the last one in the snapshot is not final. */
    public boolean hasFormingCandle() {
        return currentBucket >= 0;
    }

    // ---- internals -------------------------------------------------------

    private long bucketOf(Instant at) {
        return Math.floorDiv(at.toEpochMilli(), bucketMillis);
    }

    private void startBucket(long bucket, BigDecimal price) {
        currentBucket = bucket;
        open = high = low = close = price;
    }

    private LiveCandle currentCandle() {
        return new LiveCandle(Instant.ofEpochMilli(currentBucket * bucketMillis), open, high, low, close);
    }

    /** Move the open bucket into the finished deque and leave none open. */
    private void seal() {
        if (currentBucket < 0) {
            return;
        }
        finished.addLast(currentCandle());
        while (finished.size() > maxCandles) {
            finished.removeFirst();
        }
        currentBucket = -1;
        open = high = low = close = null;
    }
}
