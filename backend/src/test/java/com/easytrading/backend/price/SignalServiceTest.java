package com.easytrading.backend.price;

import com.easytrading.backend.price.dto.SignalResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for SignalService — no Spring context, no database, no
 * WireMock. That is possible because evaluate() only reads its arguments.
 *
 * The series below are deliberately boring: a long flat run followed by a sharp
 * move. Flat means both averages sit exactly on the flat value, so the gap
 * between them is zero right up to the move — which makes the crossing land on a
 * known candle instead of somewhere a reader has to trust.
 */
class SignalServiceTest {

    private final SignalService service = new SignalService();

    /** Candles carrying only a close — the only field the indicator reads. */
    private List<Price> series(double... closes) {
        List<Price> prices = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse("2026-01-01T00:00:00");
        for (int i = 0; i < closes.length; i++) {
            BigDecimal close = BigDecimal.valueOf(closes[i]);
            prices.add(new Price("TEST/USD", "1day", start.plusDays(i),
                    close, close, close, close, null));
        }
        return prices;
    }

    private double[] flatThen(int flatCount, double flatValue, double... tail) {
        double[] all = new double[flatCount + tail.length];
        java.util.Arrays.fill(all, 0, flatCount, flatValue);
        System.arraycopy(tail, 0, all, flatCount, tail.length);
        return all;
    }

    @Test
    void risingOutOfAFlatRunIsBuy() {
        // 27 flat candles (both averages == 100, gap zero), then three rising ones:
        // the gap turns positive inside the look-back window, so this is a crossing up.
        var prices = series(flatThen(27, 100, 110, 120, 130));

        SignalResponse signal = service.evaluate(prices, prices.size());

        assertThat(signal.verdict()).isEqualTo("BUY");
        assertThat(signal.label()).isNotBlank();
        assertThat(signal.explanation()).isNotBlank();
        // a sentence, not an indicator readout
        assertThat(signal.label()).doesNotContain("SMA").doesNotContain("10").doesNotContain("20");
    }

    @Test
    void fallingOutOfAFlatRunIsSell() {
        var prices = series(flatThen(27, 100, 90, 80, 70));

        SignalResponse signal = service.evaluate(prices, prices.size());

        assertThat(signal.verdict()).isEqualTo("SELL");
        assertThat(signal.explanation()).isNotBlank();
    }

    @Test
    void aSteadyClimbWithNoRecentCrossingIsHold() {
        // 40 candles rising by 1 each time. The short average sits above the long
        // one at every comparable point, so there is no crossing anywhere -- and
        // certainly not in the last few candles.
        double[] closes = new double[40];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100 + i;
        }

        SignalResponse signal = service.evaluate(series(closes), closes.length);

        assertThat(signal.verdict()).isEqualTo("HOLD");
        assertThat(signal.label()).contains("above");
    }

    @Test
    void aSteadyDeclineWithNoRecentCrossingIsHold() {
        double[] closes = new double[40];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 140 - i;
        }

        SignalResponse signal = service.evaluate(series(closes), closes.length);

        assertThat(signal.verdict()).isEqualTo("HOLD");
        assertThat(signal.label()).contains("below");
    }

    @Test
    void tooLittleHistoryIsNoneNotAnError() {
        // 20 candles is one short of what a 20-candle average plus a predecessor
        // needs. Neutral verdict, never an exception.
        double[] closes = new double[20];
        java.util.Arrays.fill(closes, 100);

        SignalResponse signal = service.evaluate(series(closes), closes.length);

        assertThat(signal.verdict()).isEqualTo("NONE");
        assertThat(signal.label()).isEqualTo("Not enough data yet for a signal");
        assertThat(signal.indicator()).isNull();
    }

    @Test
    void emptyAndNullAreNone() {
        assertThat(service.evaluate(List.of(), 0).verdict()).isEqualTo("NONE");
        assertThat(service.evaluate(null, 0).verdict()).isEqualTo("NONE");
    }

    @Test
    void theAveragesLineUpWithTheDisplayedCandlesAndUseTheWarmup() {
        // Closes 1..30, of which the chart draws the newest 5 (26..30). The warm-up
        // candles before them are what make both averages defined at the first one.
        double[] closes = new double[30];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = i + 1;
        }

        SignalResponse.Indicator indicator = service.evaluate(series(closes), 5).indicator();

        assertThat(indicator.name()).isEqualTo("SMA");
        assertThat(indicator.shortPeriod()).isEqualTo(SignalService.SHORT_PERIOD);
        assertThat(indicator.longPeriod()).isEqualTo(SignalService.LONG_PERIOD);
        assertThat(indicator.shortAverage()).hasSize(5).doesNotContainNull();
        assertThat(indicator.longAverage()).hasSize(5).doesNotContainNull();
        // first drawn candle (close 26): mean of 17..26 and of 7..26
        assertThat(indicator.shortAverage().get(0)).isEqualByComparingTo("21.5");
        assertThat(indicator.longAverage().get(0)).isEqualByComparingTo("16.5");
        // newest candle (close 30): mean of 21..30 and of 11..30
        assertThat(indicator.shortAverage().get(4)).isEqualByComparingTo("25.5");
        assertThat(indicator.longAverage().get(4)).isEqualByComparingTo("20.5");
    }

    @Test
    void withoutWarmupTheEarlyAveragesAreNull() {
        // All 25 candles drawn: the long average needs 20 closes, so the first 19
        // drawn candles have none.
        double[] closes = new double[25];
        java.util.Arrays.fill(closes, 100);

        SignalResponse.Indicator indicator = service.evaluate(series(closes), closes.length).indicator();

        assertThat(indicator.longAverage().get(18)).isNull();
        assertThat(indicator.longAverage().get(19)).isEqualByComparingTo("100");
        assertThat(indicator.shortAverage().get(8)).isNull();
        assertThat(indicator.shortAverage().get(9)).isEqualByComparingTo("100");
    }

    @Test
    void theWarmupAllowanceCoversTheLongPeriod() {
        // The price path fetches display + WARMUP_CANDLES. If the long period ever
        // exceeds the warm-up, the first plotted points lose their average and the
        // signal silently degrades -- this is the guard against that.
        assertThat(SignalService.WARMUP_CANDLES).isGreaterThanOrEqualTo(SignalService.LONG_PERIOD);
        assertThat(SignalService.SHORT_PERIOD).isLessThan(SignalService.LONG_PERIOD);
    }
}
