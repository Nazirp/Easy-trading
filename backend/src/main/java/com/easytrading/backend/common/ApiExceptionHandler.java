package com.easytrading.backend.common;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InvalidSearchQueryException;
import com.easytrading.backend.journal.InvalidJournalEntryException;
import com.easytrading.backend.journal.JournalEntryAlreadyLinkedException;
import com.easytrading.backend.journal.JournalEntryNotFoundException;
import com.easytrading.backend.journal.LinkedTradeNotFoundException;
import com.easytrading.backend.liveprice.LivePriceUnavailableException;
import com.easytrading.backend.price.InvalidIntervalException;
import com.easytrading.backend.trading.InsufficientFundsException;
import com.easytrading.backend.trading.InvalidTradeException;
import com.easytrading.backend.trading.TradeAlreadyClosedException;
import com.easytrading.backend.trading.TradeNotFoundException;
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
 * /search, /getPrice, the auth endpoints, the watchlist, the demo-trading
 * price feed, simulated trading and the journal — see backend/CONTRACTS.md for
 * the response bodies.
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

    // ---- SCRUM-72 / demo trading -----------------------------------------

    /**
     * UC04 6a/6b. 503 and not 500: nothing here is broken, Finnhub is
     * unreachable or rate-limited right now and there is no cached price to
     * serve instead. The next poll four seconds later may well succeed, so the
     * frontend keeps the page open and the last price on screen rather than
     * treating this as a fault.
     */
    @ExceptionHandler(LivePriceUnavailableException.class)
    public ResponseEntity<ApiError> handleLivePriceUnavailable(LivePriceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("LIVE_PRICE_UNAVAILABLE", ex.getMessage()));
    }

    /**
     * The order itself does not make sense — a missing, zero, negative or
     * over-precise quantity, or a direction that is neither LONG nor SHORT.
     * 400 rather than 409: sending this again unchanged will always fail.
     */
    @ExceptionHandler(InvalidTradeException.class)
    public ResponseEntity<ApiError> handleInvalidTrade(InvalidTradeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_BODY", ex.getMessage()));
    }

    /**
     * The margin for this trade is more than the free cash (SCRUM-83).
     *
     * 409 and not 400, because the request is perfectly well formed: it conflicts
     * with the state of the account at this moment, and the identical request may
     * succeed once another trade has closed.
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("INSUFFICIENT_FUNDS", ex.getMessage()));
    }

    /**
     * No such trade, or somebody else's — one answer for both, and never a 403,
     * which would confirm the id is real. Same rule as the journal below.
     */
    @ExceptionHandler(TradeNotFoundException.class)
    public ResponseEntity<ApiError> handleTradeNotFound(TradeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    /**
     * Closing a trade that is already closed — usually the second half of a double
     * click. The frontend treats it as "already done" and refreshes.
     */
    @ExceptionHandler(TradeAlreadyClosedException.class)
    public ResponseEntity<ApiError> handleTradeAlreadyClosed(TradeAlreadyClosedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("TRADE_ALREADY_CLOSED", ex.getMessage()));
    }

    // ---- SCRUM-81 / journal ----------------------------------------------

    /**
     * An empty or whitespace-only entry (UC05 5a). 400 rather than 409: sending it
     * again unchanged will always fail.
     */
    @ExceptionHandler(InvalidJournalEntryException.class)
    public ResponseEntity<ApiError> handleInvalidJournalEntry(InvalidJournalEntryException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_BODY", ex.getMessage()));
    }

    /**
     * An entry, or a linked trade, that is not this user's — and one that does not
     * exist at all. <b>Both answer 404 NOT_FOUND</b>, deliberately, and the two
     * exceptions share one handler so that they cannot drift into different codes.
     *
     * A 403 would be the instinctive answer for "not yours" and is the wrong one: it
     * confirms the id is real, which is exactly what somebody walking a small integer
     * id space is trying to learn. The trade case matters most — without it, posting
     * entries with tradeId 1, 2, 3... would report how many trades other people have
     * placed. Same instinct as a failed login not saying which half was wrong.
     */
    @ExceptionHandler({JournalEntryNotFoundException.class, LinkedTradeNotFoundException.class})
    public ResponseEntity<ApiError> handleJournalNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    /**
     * An edit tried to re-point an entry's trade link. A link can be added to an
     * entry that has none, never changed -- see JournalService.update.
     */
    @ExceptionHandler(JournalEntryAlreadyLinkedException.class)
    public ResponseEntity<ApiError> handleJournalAlreadyLinked(JournalEntryAlreadyLinkedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_LINKED", ex.getMessage()));
    }

    /**
     * A POST arrived with a missing or unparseable JSON body. Without this,
     * Spring answers with its own error shape, which is the one thing the
     * frontend's error handling does not know how to read.
     *
     * The message is deliberately generic. It used to name 'username' and
     * 'password', which was accurate while /api/signup and /api/login were the
     * only endpoints taking a body — and became actively misleading the moment
     * POST /api/trades arrived, since a malformed trade would have been told to
     * check fields it never sends. An error message that names the wrong fields
     * is worse than one that names none.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_BODY", "Expected a readable JSON body."));
    }
}
