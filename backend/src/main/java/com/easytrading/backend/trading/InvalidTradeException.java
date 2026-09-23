package com.easytrading.backend.trading;

/**
 * The order itself does not make sense -- a missing, zero, negative or unparseable
 * quantity, a side that is neither BUY nor SELL, or more precision than the
 * instrument can hold.
 *
 * A 400 {@code INVALID_BODY}, because unlike the two 409s this is not about the
 * account's state: sending it again unchanged will always fail.
 */
public class InvalidTradeException extends RuntimeException {
    public InvalidTradeException(String message) {
        super(message);
    }
}
