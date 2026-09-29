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
 * <b>The last three fields are null on a BUY</b>, and that is not the same as zero.
 * A purchase realises nothing -- there is no profit or loss until something is sold --
 * so the history's P&L cell for a buy is empty rather than a green "+$0.00". On a
 * SELL they carry what that sale made, the percentage against the average paid, and
 * the average itself so the page can name it in a tooltip.
 *
 * These are computed on the server (SCRUM-79, moved 2026-09-29). They used to be
 * worked out in `demo-trading.js`, which replayed the trades in JavaScript to fill
 * the same column -- a second implementation of the average-cost method, in binary
 * floating point, for money.
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
                            Instant executedAt,
                            BigDecimal realisedPnl,
                            BigDecimal realisedPnlPercent,
                            BigDecimal averageCost) {
}
