package com.easytrading.backend.journal;

/**
 * The entry itself does not make sense -- an empty or whitespace-only body
 * (UC05 5a). Maps to 400 INVALID_BODY: sending it again unchanged will always
 * fail, so it is the caller's to fix.
 *
 * The database has a CHECK on the trimmed length of `body` as well. That is the
 * backstop, not the validation: a constraint violation surfaces as a 500, which
 * the frontend has no way to render. This exception is what produces the
 * sentence a user can act on.
 */
public class InvalidJournalEntryException extends RuntimeException {

    public InvalidJournalEntryException(String message) {
        super(message);
    }
}
