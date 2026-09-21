package com.easytrading.backend.trading.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One executed trade, as the frontend receives it.
 *
 * <b>`price` is the price this trade actually executed at</b>, which can differ
 * slightly from the number that was on the user's screen when they pressed the button
 * -- a second passes and the market moves. The page shows this one in its
 * confirmation. Displaying the older number instead would be the same category of lie
 * as a carried-forward flat candle.
 *
 * <b>`executedAt` is a real zoned instant</b> -- do not append a {@code Z}. That
 * matches {@code start} and {@code priceAt} on {@code /api/getLiveChart}, and is the
 * opposite of {@code datetime} on {@code /api/getPrice}, which is zone-less UTC and
 * does need one. The field names differ on purpose so neither rule has to be
 * remembered.
 */
public record TradeResponse(Long id,
                            String symbol,
                            String side,
                            BigDecimal quantity,
                            BigDecimal price,
                            Instant executedAt) {
}
