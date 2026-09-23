package com.easytrading.backend.trading;

/**
 * The user tried to sell more than they hold (UC04 BR4 -- no short selling).
 *
 * A 409 for the same reason as {@link InsufficientFundsException}: well-formed
 * request, wrong moment. The message names the quantity actually held, because "you
 * only hold X" is something the user can act on and "invalid quantity" is not.
 */
public class InsufficientPositionException extends RuntimeException {
    public InsufficientPositionException(String message) {
        super(message);
    }
}
