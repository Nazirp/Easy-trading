package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;

/**
 * Backend &lt;-&gt; Finnhub (SCRUM-36).
 *
 * Real and wired since SCRUM-72: {@link FinnhubLivePriceClient} implements it
 * and {@link LivePriceService} calls it for the demo-trading chart's live tail.
 * The shape is exactly the one agreed in MS3 as a paper contract -- it was
 * implemented, not renamed.
 *
 * Deliberately separate from MarketDataClient (Twelve Data): two independent
 * external integrations, each doing what it is good at -- Twelve Data for the
 * past, Finnhub for the present (UC04 BR2).
 *
 * The symbol passed here is FINNHUB's spelling, not ours -- see
 * {@link DemoInstrument}. Implementations do not translate; the caller does.
 */
public interface LivePriceClient {

    /**
     * The instrument's current price: ONE price point, not a candle (UC04 BR3).
     * A single value cannot carry an open, high, low and close, which is why the
     * live chart is a line of appended points rather than a candlestick series.
     *
     * @throws RuntimeException the provider errored, rate-limited or returned
     *                          nothing usable. Callers are expected to fall back
     *                          rather than propagate -- see LivePriceService.
     */
    LivePrice getLivePrice(String finnhubSymbol);
}
