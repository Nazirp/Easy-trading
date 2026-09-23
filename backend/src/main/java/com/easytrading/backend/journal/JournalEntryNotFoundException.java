package com.easytrading.backend.journal;

/**
 * No entry with this id belongs to the logged-in user.
 *
 * <b>Deliberately one exception for two situations</b>: the entry does not exist,
 * and the entry exists but is somebody else's. They map to the same 404 with the
 * same message, because a 403 -- or a different wording -- would confirm that the
 * id is real, which is precisely what someone walking the id space is looking for.
 * Same instinct as a failed login not saying which half was wrong.
 */
public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(String message) {
        super(message);
    }
}
