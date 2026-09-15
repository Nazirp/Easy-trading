package com.easytrading.backend.watchlist;

/**
 * UC03 BR1: the instrument is already on this user's watchlist.
 *
 * Its own exception rather than a generic conflict, because the frontend shows
 * it as information ("Already on your watchlist") and not as an error — the
 * user's intent is already satisfied.
 */
public class AlreadyOnWatchlistException extends RuntimeException {
    public AlreadyOnWatchlistException(String message) {
        super(message);
    }
}
