package com.easytrading.backend.journal.dto;

import java.util.List;

/**
 * GET /api/journal.
 *
 * An object wrapping the list rather than a bare JSON array, matching
 * WatchlistResponse and TradeHistoryResponse: a top-level array cannot grow a field
 * later without breaking every caller, and an envelope can.
 *
 * An empty list means "you have written nothing", and stays distinct from "nobody is
 * logged in", which is the 401.
 */
public record JournalResponse(List<JournalEntryResponse> entries) {
}
