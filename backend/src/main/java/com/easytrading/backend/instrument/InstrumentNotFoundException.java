package com.easytrading.backend.instrument;

/**
 * Shared "not found" signal for the REST API.
 */
public class InstrumentNotFoundException extends RuntimeException {
    public InstrumentNotFoundException(String message) {
        super(message);
    }
}
