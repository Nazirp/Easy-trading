package com.easytrading.backend.journal.dto;

/**
 * PATCH /api/journal/{id}.
 *
 * One field, on purpose. An entry records what somebody thought at a moment, so the
 * instrument and the trade link are not editable -- re-pointing an entry at a
 * different trade afterwards would quietly rewrite that. A request shape with no
 * field for them is the enforcement; there is nothing to ignore and nothing to
 * remember to ignore.
 */
public record UpdateJournalEntryRequest(String body) {
}
