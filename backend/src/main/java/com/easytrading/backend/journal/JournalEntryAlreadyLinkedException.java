package com.easytrading.backend.journal;

/**
 * An edit tried to link a trade to an entry that is already linked to a different
 * one. 409 rather than 400: the request is well formed and would be fine for an entry
 * without a link -- it conflicts with the state of this one.
 */
public class JournalEntryAlreadyLinkedException extends RuntimeException {

    public JournalEntryAlreadyLinkedException(String message) {
        super(message);
    }
}
