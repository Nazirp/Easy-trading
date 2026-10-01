package com.easytrading.backend.journal.dto;

/**
 * PATCH /api/journal/{id}.
 *
 * `body` is the new text. `tradeId` is optional and only ever ADDS a link: it links a
 * trade to an entry written without one, and is refused (409 ALREADY_LINKED) for an
 * entry linked to a different trade -- an entry records what somebody thought about
 * that trade, and re-pointing it afterwards would quietly rewrite that. There is no
 * field for the symbol, and none to remove a link: nothing to ignore, nothing to
 * remember to ignore. See JournalService.update.
 */
public record UpdateJournalEntryRequest(String body, Long tradeId) {
}
