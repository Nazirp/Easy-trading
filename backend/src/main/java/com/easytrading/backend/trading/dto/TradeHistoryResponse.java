package com.easytrading.backend.trading.dto;

import java.util.List;

/**
 * Body of {@code GET /api/trades}: this user's trades for one instrument, open and
 * closed, newest first. Open ones are included so the journal can link one.
 *
 * Wrapped in an object rather than returned as a bare JSON array, like every other
 * list endpoint here: a top-level array cannot grow a field later without breaking
 * every client. An empty list is a normal 200.
 */
public record TradeHistoryResponse(String symbol, List<TradeResponse> trades) {
}
