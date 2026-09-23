package com.easytrading.backend.trading;

/**
 * Which way a simulated trade goes.
 *
 * Stored as the string "BUY" or "SELL" in {@code trade.side}, matched by a CHECK
 * constraint rather than a Postgres ENUM type -- see db/schema.sql for why. The
 * names here and the values in that CHECK have to agree; there is no converter
 * doing it for us, which is the deliberate cost of keeping it a VARCHAR.
 *
 * There is no SHORT. UC04 BR4 says no margin and no short selling: a sell can
 * only close a position the user actually holds.
 */
public enum TradeSide {
    BUY,
    SELL
}
