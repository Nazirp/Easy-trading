package com.easytrading.backend.instrument;

/** Empty (or missing) query submitted. */
public class InvalidSearchQueryException extends RuntimeException {
    public InvalidSearchQueryException(String message) {
        super(message);
    }
}
