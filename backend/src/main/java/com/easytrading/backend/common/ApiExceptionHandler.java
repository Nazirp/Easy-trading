package com.easytrading.backend.common;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InvalidSearchQueryException;
import com.easytrading.backend.price.InvalidIntervalException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central mapping from domain exceptions to the API's error shape, shared by
 * /search and /getPrice — see backend/CONTRACTS.md for the response bodies.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidSearchQueryException.class)
    public ResponseEntity<ApiError> handleInvalidQuery(InvalidSearchQueryException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_QUERY", ex.getMessage()));
    }

    @ExceptionHandler(InvalidIntervalException.class)
    public ResponseEntity<ApiError> handleInvalidInterval(InvalidIntervalException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_INTERVAL", ex.getMessage()));
    }

    @ExceptionHandler(InstrumentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(InstrumentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage()));
    }
}
