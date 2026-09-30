package com.easytrading.backend.trading;

import java.math.BigDecimal;

/**
 * Which way a trade is betting.
 *
 * A {@code LONG} profits when the price rises and a {@code SHORT} profits when it
 * falls. A short is a first-class opening, not the sale of something held -- this is
 * the CFD model, in which buys and sells are never paired with each other.
 *
 * Stored as the enum's name in a {@code VARCHAR} with a {@code CHECK}, like
 * {@code price_candle.interval}, rather than a Postgres {@code ENUM} type: a CHECK is
 * one line to change and maps onto a Java enum with no special JDBC handling.
 *
 * Not to be confused with the signal's {@code BUY} / {@code SELL} / {@code HOLD}
 * verdict in {@code SignalService}, which is a different concept that happens to
 * share two words.
 */
public enum TradeDirection {

    LONG(BigDecimal.ONE),
    SHORT(BigDecimal.ONE.negate());

    private final BigDecimal sign;

    TradeDirection(BigDecimal sign) {
        this.sign = sign;
    }

    /** +1 if this direction profits from a rising price, -1 if from a falling one. */
    public BigDecimal sign() {
        return sign;
    }
}
