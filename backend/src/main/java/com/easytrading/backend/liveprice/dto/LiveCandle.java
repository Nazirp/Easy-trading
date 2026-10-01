package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One candle on the demo-trading chart, whatever produced it.
 *
 * `start` is the instant its bucket opens, aligned to the wall clock — which is
 * what lets a candle aggregated from the trade stream sit on exactly the same
 * minute grid as one fetched from Twelve Data, so the two halves of the chart
 * line up rather than merely sitting next to each other.
 */
public record LiveCandle(Instant start,
                         BigDecimal open,
                         BigDecimal high,
                         BigDecimal low,
                         BigDecimal close) {}
