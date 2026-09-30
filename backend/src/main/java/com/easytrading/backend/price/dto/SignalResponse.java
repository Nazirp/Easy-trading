package com.easytrading.backend.price.dto;

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
public record SignalResponse(String verdict, String label, String explanation) {

    public static SignalResponse buy(String label, String explanation) {
        return new SignalResponse("BUY", label, explanation);
    }

    public static SignalResponse sell(String label, String explanation) {
        return new SignalResponse("SELL", label, explanation);
    }

    public static SignalResponse hold(String label, String explanation) {
        return new SignalResponse("HOLD", label, explanation);
    }

    public static SignalResponse notEnoughData() {
        return new SignalResponse("NONE", "Not enough data yet for a signal", null);
    }
}
