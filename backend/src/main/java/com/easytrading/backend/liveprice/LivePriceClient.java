package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;

/**
 * Backend &lt;-&gt; Finnhub.
 *
 * Deliberately separate from MarketDataClient (Twelve Data): two independent
 * external integrations, each doing what it is good at -- Twelve Data for the
 * past, Finnhub for the present.
 *
 * The symbol passed here is FINNHUB's spelling, not ours -- see
 * {@link DemoInstrument}. Implementations do not translate; the caller does.
 */
public interface LivePriceClient {

    /**
     * The instrument's current price: ONE price point, not a candle.
     *
     * @throws RuntimeException the provider errored, rate-limited or returned
     *                          nothing usable. Callers are expected to fall back
     *                          rather than propagate -- see LivePriceService.
     */
    LivePrice getLivePrice(String finnhubSymbol);
}
