package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LiveCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bucketing, in isolation.
 *
 * No Spring, no clock, no network: every instant is supplied, so "an hour of
 * silence passed" is a value rather than an hour of waiting.
 */
class LiveCandleAggregatorTest {

    private static final long ONE_MINUTE = 60_000L;

    /** Exactly on a minute boundary. */
    private static final Instant BASE = Instant.parse("2026-09-18T09:00:00Z");

    private final LiveCandleAggregator aggregator = new LiveCandleAggregator(ONE_MINUTE, 30);

    private static Instant at(long millisAfterBase) {
        return BASE.plusMillis(millisAfterBase);
    }

    private void trade(String price, long millisAfterBase) {
        aggregator.accept(new BigDecimal(price), at(millisAfterBase));
    }

    // ---- the happy path --------------------------------------------------

    @Test
    void buildsOpenHighLowCloseFromTheTradesInOneBucket() {
        trade("100", 0);
        trade("105", 10_000);
        trade("95", 30_000);
        trade("102", 59_999);

        List<LiveCandle> candles = aggregator.snapshot(at(59_999));

        assertThat(candles).hasSize(1);
        LiveCandle candle = candles.get(0);
        assertThat(candle.open()).isEqualByComparingTo("100");   // first trade
        assertThat(candle.high()).isEqualByComparingTo("105");   // not the last, not the first
        assertThat(candle.low()).isEqualByComparingTo("95");
        assertThat(candle.close()).isEqualByComparingTo("102");  // last trade
        assertThat(aggregator.hasFormingCandle()).isTrue();
    }

    @Test
    void bucketsAlignToTheWallClockNotToTheFirstTrade() {
        // First trade lands 37s into a minute. The candle must still start on the
        // minute, or two viewers who connected at different moments would see the
        // same market on differently-aligned candles -- and, just as important,
        // the live candles would not sit on Twelve Data's grid, so the backfilled
        // half of the chart and the live half could not be joined.
        trade("100", 37_000);

        List<LiveCandle> candles = aggregator.snapshot(at(37_000));

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).start()).isEqualTo(BASE);
        assertThat(BASE.toEpochMilli() % ONE_MINUTE).isZero();
    }

    @Test
    void aNewBucketSealsThePreviousOne() {
        trade("100", 0);
        trade("110", 50_000);
        trade("108", 60_000);   // next minute

        List<LiveCandle> candles = aggregator.snapshot(at(60_000));

        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).close()).isEqualByComparingTo("110");
        assertThat(candles.get(0).start()).isEqualTo(BASE);
        assertThat(candles.get(1).open()).isEqualByComparingTo("108");
        assertThat(candles.get(1).start()).isEqualTo(BASE.plusSeconds(60));
        // A new candle opens at its own first trade, NOT at the previous close --
        // a gap between them is real information, not something to smooth away.
        assertThat(candles.get(1).open()).isNotEqualByComparingTo(candles.get(0).close());
    }

    @Test
    void theLastCandleIsTheOneStillForming() {
        trade("100", 0);
        trade("110", 70_000);

        List<LiveCandle> candles = aggregator.snapshot(at(70_000));

        // Only the last may still change; everything before it is sealed.
        assertThat(aggregator.hasFormingCandle()).isTrue();
        assertThat(candles.get(candles.size() - 1).start()).isEqualTo(BASE.plusSeconds(60));

        trade("120", 80_000);
        List<LiveCandle> after = aggregator.snapshot(at(80_000));
        assertThat(after).hasSameSizeAs(candles);
        assertThat(after.get(after.size() - 1).close()).isEqualByComparingTo("120");
    }

    @Test
    void aMinuteThatIsOverIsSealedEvenWithNoLaterTrade() {
        trade("100", 0);

        // No further trade ever arrives, but the minute has ended, so the candle
        // is finished -- the chart must not show it as still moving.
        List<LiveCandle> candles = aggregator.snapshot(at(5 * ONE_MINUTE));

        assertThat(candles).hasSize(1);
        assertThat(aggregator.hasFormingCandle()).isFalse();
    }

    // ---- silence ---------------------------------------------------------

    @Test
    void aMinuteWithNoTradesProducesNoCandleAtAll() {
        trade("100", 0);
        trade("120", 3 * ONE_MINUTE);   // two silent minutes

        List<LiveCandle> candles = aggregator.snapshot(at(3 * ONE_MINUTE));

        // Two candles, not four. Carrying the previous close forward would assert
        // that the market did not move during minutes when the truth is that we
        // were not listening.
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).start()).isEqualTo(BASE);
        assertThat(candles.get(1).start()).isEqualTo(BASE.plusSeconds(180));
        // The gap is visible in the timestamps, which is how the frontend knows to
        // leave a hole rather than drawing two adjacent candles.
        assertThat(candles.get(1).start()).isAfter(candles.get(0).start().plusSeconds(60));
    }

    @Test
    void aLongSilenceLeavesTheSeriesShortRatherThanInventingHalfAnHour() {
        trade("100", 0);

        List<LiveCandle> candles = aggregator.snapshot(at(60 * ONE_MINUTE));

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).start()).isEqualTo(BASE);
    }

    // ---- bounds and bad input -------------------------------------------

    @Test
    void theWindowDropsTheOldestCandles() {
        LiveCandleAggregator small = new LiveCandleAggregator(ONE_MINUTE, 3);
        for (int i = 0; i < 10; i++) {
            small.accept(new BigDecimal(100 + i), at(i * ONE_MINUTE));
        }

        List<LiveCandle> candles = small.snapshot(at(9 * ONE_MINUTE));

        // Bounded, and it is the RECENT end that survives.
        assertThat(candles).hasSizeLessThanOrEqualTo(4);
        assertThat(candles.get(candles.size() - 1).close()).isEqualByComparingTo("109");
    }

    @Test
    void aTradeForAnAlreadySealedBucketIsDropped() {
        trade("100", 0);
        trade("110", 2 * ONE_MINUTE);   // seals minute 0

        trade("999", 10_000);           // arrives late, belongs to minute 0

        List<LiveCandle> candles = aggregator.snapshot(at(2 * ONE_MINUTE));

        // Rewriting a sealed candle would change history under a chart that has
        // already drawn it.
        assertThat(candles.get(0).high()).isEqualByComparingTo("100");
        assertThat(candles.get(candles.size() - 1).high()).isEqualByComparingTo("110");
        // ...and a late trade must not make the feed look fresher than it is.
        assertThat(aggregator.lastTradeAt()).isEqualTo(at(2 * ONE_MINUTE));
    }

    @Test
    void nothingIsReturnedBeforeTheFirstTrade() {
        assertThat(aggregator.snapshot(at(60_000))).isEmpty();
        assertThat(aggregator.hasFormingCandle()).isFalse();
        assertThat(aggregator.lastTradeAt()).isNull();
    }

    @Test
    void nullsAndNonPositivePricesAreIgnored() {
        aggregator.accept(null, BASE);
        aggregator.accept(new BigDecimal("100"), null);
        aggregator.accept(BigDecimal.ZERO, BASE);
        aggregator.accept(new BigDecimal("-5"), BASE);

        assertThat(aggregator.snapshot(BASE)).isEmpty();
        assertThat(aggregator.lastTradeAt()).isNull();
    }

    @Test
    void aNonsenseConfigurationFailsLoudlyAtConstruction() {
        assertThatThrownBy(() -> new LiveCandleAggregator(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveCandleAggregator(ONE_MINUTE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotIsIdempotent() {
        trade("100", 0);
        List<LiveCandle> first = aggregator.snapshot(at(2 * ONE_MINUTE));
        List<LiveCandle> second = aggregator.snapshot(at(2 * ONE_MINUTE));

        // snapshot() seals a finished minute as a side effect, so calling it twice
        // must not seal twice or the series would grow on every poll -- and the
        // page polls it once a second.
        assertThat(second).hasSameSizeAs(first);
        assertThat(second.get(second.size() - 1).start())
                .isEqualTo(first.get(first.size() - 1).start());
    }
}
