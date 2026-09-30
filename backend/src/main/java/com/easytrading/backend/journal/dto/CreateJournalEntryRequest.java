package com.easytrading.backend.journal.dto;

/**
 * POST /api/journal.
 *
 * `symbol` and `tradeId` are both optional and both usually absent -- the common
 * entry ("I keep buying tops, slow down") links neither, which is why neither is
 * required rather than one being a special case.
 *
 * When `tradeId` is present, `symbol` is IGNORED: the trade already knows which
 * instrument it was, and taking the client's word for it would create two
 * statements about one fact that can disagree. See JournalService.create.
 *
 * There is no price or signal field. A linked trade already carries the price and the
 * instant.
 */
public record CreateJournalEntryRequest(String body, String symbol, Long tradeId) {
}
