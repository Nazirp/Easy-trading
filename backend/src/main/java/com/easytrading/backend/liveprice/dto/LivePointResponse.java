package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One backfilled point on the demo-trading chart: a 1-minute candle reduced to
 * the moment and its closing price.
 *
 * The datetime is a LocalDateTime with no zone, exactly as `/api/getPrice`
 * returns for 2h/4h candles, and it carries the same convention: intraday values
 * are UTC because the client asks Twelve Data for UTC (backend/CONTRACTS.md
 * section 2). The frontend must append a `Z` before parsing it, or the points
 * land hours away from the live ones next to them.
 */
public record LivePointResponse(LocalDateTime datetime, BigDecimal price) {}
