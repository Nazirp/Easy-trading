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
 *
 * <p><b>Changed 2026-09-19:</b> the {@code flat} flag is gone. It used to mark a
 * bucket in which nothing traded, carried forward at the previous close. That
 * made sense while the live chart was a LINE, where a gap breaks the line and
 * reads as a fault. A candle chart has no such problem: a missing candle is
 * simply a missing candle, and that is the honest picture, because a flat candle
 * asserts "the market did not move this minute" when what actually happened is
 * that our feed was not listening. See {@code LiveCandleAggregator}.
 */
public record LiveCandle(Instant start,
                         BigDecimal open,
                         BigDecimal high,
                         BigDecimal low,
                         BigDecimal close) {}
