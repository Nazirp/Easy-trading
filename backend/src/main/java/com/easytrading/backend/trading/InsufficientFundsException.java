package com.easytrading.backend.trading;

/**
 * Opening this trade would reserve more margin than the free cash covers. Leverage is
 * 1:1, so the margin is the trade's full notional, {@code entryPrice x quantity}.
 *
 * A 409 rather than a 400: the request is well formed, it just conflicts with the state
 * of the account, and the same request may succeed once another trade has closed.
 *
 * {@code app_user}'s {@code CHECK (cash_balance >= 0)} is the backstop for the same
 * rule, but it never fires in normal use: the cash is taken by a relative
 * {@code UPDATE} guarded by {@code cash_balance + delta >= 0}, and when that matches no
 * row this exception is what the caller sees -- a readable 409 instead of a constraint
 * violation surfacing as a 500.
 */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
