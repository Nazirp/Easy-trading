package com.easytrading.backend.trading;

/**
 * The trade is already closed -- most often by the same user's earlier click.
 *
 * A 409, not a 400: the request is well formed, it conflicts with the trade's current
 * state. The frontend treats it as "already done" and refreshes rather than showing an
 * error. It is also what the losing side of a double click receives, because the close
 * is one guarded {@code UPDATE} and the second request matches no row.
 */
public class TradeAlreadyClosedException extends RuntimeException {
    public TradeAlreadyClosedException(String message) {
        super(message);
    }
}
