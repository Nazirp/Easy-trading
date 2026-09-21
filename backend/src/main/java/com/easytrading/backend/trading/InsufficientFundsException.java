package com.easytrading.backend.trading;

/**
 * The user tried to buy more than their virtual cash covers (UC04 BR4 -- no margin).
 *
 * A 409 rather than a 400: the request is perfectly well formed, it just conflicts
 * with the state of the account. The frontend renders the two differently, and a
 * caller retrying after selling something would be right to expect this one to start
 * working without changing anything they sent.
 *
 * {@code app_user}'s {@code CHECK (cash_balance >= 0)} is the backstop for the same
 * rule. It exists in case this check is ever bypassed, but it must not be the thing
 * that fires in normal use: a constraint violation surfaces as a 500 with nothing the
 * frontend can interpret, which is the bug that made the 1w chart return a 500 back
 * in MS4.
 */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
