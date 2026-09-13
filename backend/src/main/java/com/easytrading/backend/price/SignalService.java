package com.easytrading.backend.price;

import com.easytrading.backend.price.dto.SignalResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * The plain-language signal (SCRUM-46 / SCRUM-64): one moving-average crossover,
 * turned into a sentence a beginner can act on.
 *
 * DELIBERATELY A PURE FUNCTION. It takes a list of candles and returns a verdict
 * — no repository, no API client, no Spring dependencies of its own. That is the
 * layered-architecture requirement (UC02 BR3) made structural rather than
 * conventional: this class cannot reach the database or the provider even by
 * accident. It also means the whole thing is unit-testable with a handcrafted
 * list, no Spring context and no Postgres (see SignalServiceTest).
 *
 * THE INDICATOR, and why these periods (decision recorded on SCRUM-64):
 * a 10-candle average against a 20-candle average. The textbook pair is 50/200,
 * which needs 200 candles of history — but the 1yr range is only 52 candles, so
 * 50/200 would return NONE on the range users are most likely to open first.
 * 10/20 produces a verdict on every one of the four ranges, and 20 fits exactly
 * inside the warm-up allowance the price path already fetches (WARMUP_CANDLES
 * below), so no extra provider requests were needed to add the signal.
 *
 * A second indicator (RSI and friends) is explicitly a later story.
 */
@Service
public class SignalService {

    /** The fast average — reacts to recent moves. */
    static final int SHORT_PERIOD = 10;

    /** The slow average — the baseline the fast one is compared against. */
    static final int LONG_PERIOD = 20;

    /**
     * How many of the most recent candles are examined for a crossing.
     *
     * A crossing that happened on exactly the last candle is a rare event, so
     * reporting BUY/SELL only in that instant would leave the chart showing HOLD
     * almost always. Looking back a few candles lets a fresh crossing stay
     * visible long enough for a user to actually see it.
     */
    static final int CROSS_LOOKBACK = 3;

    /**
     * Candles the price path must fetch BEYOND the display window so that an
     * average is defined at the very first plotted point.
     *
     * This lives here rather than in PriceService because it is a property of
     * the indicator, not of the chart: change LONG_PERIOD and the warm-up has to
     * change with it, which is a mistake waiting to happen if the two numbers
     * sit in different files. PriceService.fetchSize() reads it from here.
     */
    public static final int WARMUP_CANDLES = LONG_PERIOD;

    /** Enough precision that two averages of similar candles still compare correctly. */
    private static final int SCALE = 10;

    /**
     * @param candlesOldestFirst the display window PLUS its warm-up candles,
     *                           oldest first. Passing only the display window
     *                           still works but wastes the warm-up: the first
     *                           LONG_PERIOD points would have no average.
     */
    public SignalResponse evaluate(List<Price> candlesOldestFirst) {
        if (candlesOldestFirst == null) {
            return SignalResponse.notEnoughData();
        }

        List<BigDecimal> closes = candlesOldestFirst.stream()
                .map(Price::getClose)
                .filter(Objects::nonNull)
                .toList();

        // LONG_PERIOD candles produce the first average; one more gives a previous
        // value to compare it against, which is the minimum for detecting a cross.
        if (closes.size() < LONG_PERIOD + 1) {
            return SignalResponse.notEnoughData();
        }

        int last = closes.size() - 1;
        int firstComparable = LONG_PERIOD; // earliest index that has a predecessor with a long average
        int from = Math.max(firstComparable, last - CROSS_LOOKBACK + 1);

        int crossing = 0; // +1 crossed up, -1 crossed down, 0 none in the window
        for (int i = from; i <= last; i++) {
            int before = signOfGap(closes, i - 1);
            int after = signOfGap(closes, i);
            if (before <= 0 && after > 0) {
                crossing = 1;
            } else if (before >= 0 && after < 0) {
                crossing = -1;
            }
            // a later iteration overwrites an earlier one: the most recent crossing wins
        }

        if (crossing > 0) {
            return SignalResponse.buy("Trending up",
                    "Its recent average has just risen above its longer-term average, "
                            + "which often comes at the start of an upward move.");
        }
        if (crossing < 0) {
            return SignalResponse.sell("Trending down",
                    "Its recent average has just fallen below its longer-term average, "
                            + "which often comes at the start of a downward move.");
        }

        int position = signOfGap(closes, last);
        if (position > 0) {
            return SignalResponse.hold("Steady, above its longer-term average",
                    "It has been running above its longer-term average with no recent change of direction.");
        }
        if (position < 0) {
            return SignalResponse.hold("Steady, below its longer-term average",
                    "It has been running below its longer-term average with no recent change of direction.");
        }
        return SignalResponse.hold("Steady",
                "Its recent and longer-term averages are level with each other — no clear direction.");
    }

    /** Sign of (short average − long average) at one point in the series. */
    private int signOfGap(List<BigDecimal> closes, int index) {
        return average(closes, index, SHORT_PERIOD).compareTo(average(closes, index, LONG_PERIOD));
    }

    /** Mean of the `period` closes ending at (and including) `endIndex`. */
    private BigDecimal average(List<BigDecimal> closes, int endIndex, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            sum = sum.add(closes.get(i));
        }
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }
}
