package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Body of {@code GET /api/getLivePrice} -- see backend/CONTRACTS.md section 1.
 *
 * ONE price, not a candle (UC04 BR3): there is no open/high/low/close here
 * because a single quote cannot carry them.
 *
 * `outdated` is true when Finnhub could not be reached on this cycle and the
 * last good price is being re-served (UC04 6a/6b). The status is still 200 --
 * the request was fine and there IS a price, it is just not brand new -- so the
 * frontend keeps drawing and shows a small "may be outdated" note rather than
 * treating it as a failure. A price the frontend should not append twice is one
 * with an unchanged `timestamp`.
 */
public record LivePriceResponse(String symbol, BigDecimal price, Instant timestamp, boolean outdated) {}
