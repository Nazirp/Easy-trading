package com.easytrading.backend.price.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The plain-language signal that always travels with the price data (chart and
 * signal are one view, never two separate calls).
 *
 * `verdict` is one of BUY | SELL | HOLD | NONE — the four strings live here, in
 * the factory methods below, so nothing else in the codebase spells them.
 *
 * `label` is the sentence shown to the user and `explanation` the short "why".
 * Both are written here rather than in the frontend because the backend owns the
 * verdict set, and whoever owns the set owns the words for it. Neither ever
 * contains a raw indicator number — no unexplained jargon.
 *
 * NONE is the neutral "not enough data yet" state. It is
 * a normal 200 response, not an error, and the chart still renders beside it.
 */
public record SignalResponse(String verdict, String label, String explanation, Indicator indicator) {

    /**
     * The numbers behind the verdict, for the chart's explainer. Kept apart from
     * `label` and `explanation`, which stay free of indicator numbers.
     *
     * `shortAverage` and `longAverage` hold one value per displayed candle, in the
     * same order as `prices`. They are computed with the warm-up candles, so both are
     * defined from the first plotted candle. Null when there is no verdict (NONE).
     */
    public record Indicator(String name, int shortPeriod, int longPeriod, int lookback,
                            List<BigDecimal> shortAverage, List<BigDecimal> longAverage) {}

    public static SignalResponse buy(String label, String explanation, Indicator indicator) {
        return new SignalResponse("BUY", label, explanation, indicator);
    }

    public static SignalResponse sell(String label, String explanation, Indicator indicator) {
        return new SignalResponse("SELL", label, explanation, indicator);
    }

    public static SignalResponse hold(String label, String explanation, Indicator indicator) {
        return new SignalResponse("HOLD", label, explanation, indicator);
    }

    public static SignalResponse notEnoughData() {
        return new SignalResponse("NONE", "Not enough data yet for a signal", null, null);
    }
}
