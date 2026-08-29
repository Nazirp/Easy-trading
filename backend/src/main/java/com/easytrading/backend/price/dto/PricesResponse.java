package com.easytrading.backend.price.dto;

import java.util.List;

/**
 * Response of GET /getPrice. `prices` can legitimately be empty (the instrument
 * exists but the provider had nothing for it) — that's not an error. `signal`
 * is always present; see SignalResponse.
 */
public record PricesResponse(String symbol, String interval, List<PriceResponse> prices, SignalResponse signal) {}
