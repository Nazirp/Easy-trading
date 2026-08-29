package com.easytrading.backend.instrument;

/** UC01 extension 2a: empty (or missing) query submitted. */
public class InvalidSearchQueryException extends RuntimeException {
    public InvalidSearchQueryException(String message) {
        super(message);
    }
}
