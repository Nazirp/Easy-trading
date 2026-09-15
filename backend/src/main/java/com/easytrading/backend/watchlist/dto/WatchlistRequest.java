package com.easytrading.backend.watchlist.dto;

/**
 * Request body of POST /api/watchlist — see backend/CONTRACTS.md.
 *
 * The symbol travels in the body rather than the URL for the same reason it
 * does on DELETE: symbols like "BTC/USD" contain a slash.
 */
public record WatchlistRequest(String symbol) {}
