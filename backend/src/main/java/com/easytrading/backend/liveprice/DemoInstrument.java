package com.easytrading.backend.liveprice;

import com.easytrading.backend.instrument.InstrumentNotFoundException;

/**
 * The one instrument demo trading works on, and how the two providers spell it.
 *
 * Demo trading is BTC/USD only (UC04 BR6, decided 2026-09-15). The reason is not
 * the chart -- it is live P&L: a portfolio holding several instruments has to
 * price all of them every 5 seconds, which is ~12 Finnhub requests a minute
 * EACH against a free tier of ~30-60. BTC/USD specifically because crypto trades
 * 24/7, so the demo moves at any hour of the day a tutor opens it.
 *
 * FINNHUB_SYMBOL used to be a column, `instrument.finnhub_symbol`. It was
 * removed on 2026-09-15: one useful value spread across six rows is not a model
 * of anything, and nothing outside this feature ever read it.
 *
 * If a second instrument is ever added, this becomes a Map<String, String> (or
 * the column comes back) -- and {@link #requireSupported} becomes a lookup
 * instead of an equality check. Until then, two constants say what is true.
 */
public final class DemoInstrument {

    /** Our own symbol, as it appears in the `instrument` table and in every other endpoint. */
    public static final String SYMBOL = "BTC/USD";

    /** The same instrument as Finnhub spells it: exchange-prefixed, no slash. */
    public static final String FINNHUB_SYMBOL = "BINANCE:BTCUSDT";

    private DemoInstrument() {
    }

    /**
     * Guards both demo-trading endpoints.
     *
     * Anything other than BTC/USD is a 404 rather than a silent redirect to
     * BTC/USD, so a frontend bug shows up as a visible error instead of a chart
     * quietly showing the wrong instrument. It also keeps the backfill from
     * being a general-purpose 1-minute proxy that any caller could point at any
     * symbol and burn the Twelve Data quota with.
     *
     * @throws InstrumentNotFoundException the symbol is not the demo instrument
     */
    public static String requireSupported(String symbol) {
        if (!SYMBOL.equalsIgnoreCase(symbol == null ? null : symbol.trim())) {
            throw new InstrumentNotFoundException("Demo trading is available for " + SYMBOL + " only.");
        }
        return SYMBOL;
    }
}
