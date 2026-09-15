package com.easytrading.backend.common;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InvalidSearchQueryException;
import com.easytrading.backend.price.InvalidIntervalException;
import com.easytrading.backend.user.InvalidCredentialsException;
import com.easytrading.backend.user.InvalidRegistrationException;
import com.easytrading.backend.user.NotAuthenticatedException;
import com.easytrading.backend.user.UsernameTakenException;
import com.easytrading.backend.watchlist.AlreadyOnWatchlistException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central mapping from domain exceptions to the API's error shape, shared by
 * /search, /getPrice, the auth endpoints and the watchlist — see
 * backend/CONTRACTS.md for the
 * response bodies.
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

    // ---- SCRUM-39 / auth ------------------------------------------------

    @ExceptionHandler(InvalidRegistrationException.class)
    public ResponseEntity<ApiError> handleInvalidRegistration(InvalidRegistrationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REGISTRATION", ex.getMessage()));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ApiError> handleUsernameTaken(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("USERNAME_TAKEN", ex.getMessage()));
    }

    /**
     * Wrong credentials. The message comes from the exception and is deliberately
     * the same for an unknown username and a wrong password — see
     * InvalidCredentialsException.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("INVALID_CREDENTIALS", ex.getMessage()));
    }

    @ExceptionHandler(NotAuthenticatedException.class)
    public ResponseEntity<ApiError> handleNotAuthenticated(NotAuthenticatedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("NOT_AUTHENTICATED", ex.getMessage()));
    }

    // ---- SCRUM-22 / watchlist --------------------------------------------

    /**
     * UC03 BR1. A conflict rather than an error the user has to fix: the
     * instrument they asked for is already saved, so what they wanted is
     * already true.
     */
    @ExceptionHandler(AlreadyOnWatchlistException.class)
    public ResponseEntity<ApiError> handleAlreadyOnWatchlist(AlreadyOnWatchlistException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_ON_WATCHLIST", ex.getMessage()));
    }

    /**
     * A POST arrived with a missing or unparseable JSON body. Without this,
     * Spring answers with its own error shape, which is the one thing the
     * frontend's error handling does not know how to read.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_BODY", "Expected a JSON body with 'username' and 'password'."));
    }
}
