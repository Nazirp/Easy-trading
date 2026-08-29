package com.easytrading.backend.instrument;

/**
 * Shared "not found" signal for the REST API: a search with zero matches
 * (UC01 extension 7a) and a /prices request for an unknown symbol both use
 * this. Mirrors search_instrument()'s NULL return in db/schema.sql -- same
 * idea, one consistent "this doesn't exist" convention across the DB
 * helper and the real API, as flagged when that function was written.
 */
public class InstrumentNotFoundException extends RuntimeException {
    public InstrumentNotFoundException(String message) {
        super(message);
    }
}
