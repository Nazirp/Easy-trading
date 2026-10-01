package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One candle as the frontend receives it.
 *
 * <b>`start` is a real zoned instant</b> — do NOT append a `Z` before parsing it.
 * That is the opposite of `datetime` on `/api/getPrice`, which is zone-less UTC
 * and does need one. Different field name and different type on purpose, so
 * neither rule has to be remembered.
 *
 * `live` is true for a candle aggregated from every trade on the socket, false
 * for one Twelve Data summarised. Both are 1-minute candles on the same wall-clock
 * grid, so they line up exactly — but the two providers will not agree to the
 * last decimal, so the frontend marks where one becomes the other rather than
 * letting a small step there read as market movement.
 *
 * `forming` is true for at most one candle, the last: its high, low and close are
 * still moving as trades arrive. Redraw it in place; do not append a new one
 * until a candle with a later `start` shows up.
 */
public record LiveChartCandle(Instant start,
                              BigDecimal open,
                              BigDecimal high,
                              BigDecimal low,
                              BigDecimal close,
                              boolean live,
                              boolean forming) {}
