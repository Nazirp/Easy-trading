package com.easytrading.backend.trading;

/**
 * The order itself does not make sense -- a missing, zero, negative or over-precise
 * quantity, or a direction that is neither LONG nor SHORT.
 *
 * A 400 {@code INVALID_BODY}, because unlike the 409s this is not about the account's
 * state: sending it again unchanged will always fail.
 */
public class InvalidTradeException extends RuntimeException {
    public InvalidTradeException(String message) {
        super(message);
    }
}
