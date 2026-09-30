package com.easytrading.backend.price;

/** Requested interval isn't one the app supports (see Interval). */
public class InvalidIntervalException extends RuntimeException {
    public InvalidIntervalException(String message) {
        super(message);
    }
}
