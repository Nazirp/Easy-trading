package com.easytrading.backend.liveprice;

/**
 * Finnhub could not be reached (or gave nothing usable) AND there is no cached
 * price to fall back on -- see LivePriceService.
 *
 * Maps to 503 LIVE_PRICE_UNAVAILABLE, not 500: nothing in this application is
 * broken, an upstream provider is unavailable or rate-limited right now, and the
 * next poll four seconds later may well succeed. UC04 extensions 6a/6b say the
 * page stays open and keeps showing the last price it had; a 503 with the usual
 * ApiError body is what lets the frontend tell "no price yet" apart from "this
 * request was wrong".
 */
public class LivePriceUnavailableException extends RuntimeException {

    public LivePriceUnavailableException(String message) {
        super(message);
    }

    public LivePriceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
