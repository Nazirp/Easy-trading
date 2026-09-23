package com.easytrading.backend.trading.dto;

import java.util.List;

/**
 * Body of {@code GET /api/trades}: this user's trades for one instrument, newest
 * first.
 *
 * Wrapped in an object rather than returned as a bare JSON array, like every other
 * list endpoint here. A top-level array cannot grow a field later without breaking
 * every client, and "we might need to add a total or a cursor" is a safe bet.
 *
 * An empty list is a normal 200 -- a user who has not traded yet is not an error.
 */
public record TradeHistoryResponse(String symbol, List<TradeResponse> trades) {
}
