package com.easytrading.backend.watchlist.dto;

import com.easytrading.backend.instrument.dto.InstrumentMatchResponse;

import java.util.List;

/**
 * Response body of GET /api/watchlist — see backend/CONTRACTS.md.
 *
 * Items are {@link InstrumentMatchResponse}, the exact record /api/search
 * already returns, reused rather than copied: a watchlist row and a search
 * result describe the same thing, so the frontend can render either with one
 * piece of code, and a future field can never appear on one and not the other.
 */
public record WatchlistResponse(List<InstrumentMatchResponse> items) {}
