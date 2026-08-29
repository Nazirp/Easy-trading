package com.easytrading.backend.price.dto;

/**
 * The plain-language signal that always travels with the price data (SCRUM-20 /
 * UC02 BR1: chart and signal are one view, never two separate calls).
 *
 * verdict is one of: BUY | SELL | HOLD | NONE.
 * NONE is the neutral "not enough data yet" state from UC02 extension 5a — a
 * normal success response, not an error; the chart still renders.
 *
 * MS3 always returns NONE: signal computation itself is SCRUM-20 work for MS4.
 * The field exists now so the response shape doesn't change when it lands —
 * only the values do.
 */
public record SignalResponse(String verdict, String label, String explanation) {

    public static SignalResponse notEnoughData() {
        return new SignalResponse("NONE", "Not enough data yet for a signal", null);
    }
}
