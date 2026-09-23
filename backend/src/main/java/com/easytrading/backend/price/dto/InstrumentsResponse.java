package com.easytrading.backend.price.dto;

import java.util.List;

/**
 * GET /api/instruments. Wrapped in an object rather than returned as a bare
 * array, matching SearchResponse and PricesResponse: a top-level JSON array
 * cannot gain a field later without breaking every client, an object can.
 */
public record InstrumentsResponse(List<InstrumentQuoteResponse> instruments) {}
