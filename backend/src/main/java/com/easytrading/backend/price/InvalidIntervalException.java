package com.easytrading.backend.price;

/** Requested interval isn't one of the three the app supports (see Interval). */
public class InvalidIntervalException extends RuntimeException {
    public InvalidIntervalException(String message) {
        super(message);
    }
}
