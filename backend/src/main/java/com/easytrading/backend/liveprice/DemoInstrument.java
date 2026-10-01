package com.easytrading.backend.liveprice;

import com.easytrading.backend.instrument.InstrumentNotFoundException;

/**
 * The one instrument demo trading works on, and how the two providers spell it.
 *
 * Demo trading is BTC/USD only. BTC/USD specifically because crypto trades
 * 24/7, so the demo moves at any hour of the day a tutor opens it.
 */
public final class DemoInstrument {

    /** Our own symbol, as it appears in the `instrument` table and in every other endpoint. */
    public static final String SYMBOL = "BTC/USD";

    /** The same instrument as Finnhub spells it: exchange-prefixed, no slash. */
    public static final String FINNHUB_SYMBOL = "BINANCE:BTCUSDT";

    private DemoInstrument() {
    }

    /**
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
