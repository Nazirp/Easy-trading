package com.easytrading.backend.trading.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One trade -- a position -- as the frontend receives it.
 *
 * {@code exitPrice} and {@code closedAt} are both null while the trade is open and
 * both set once it closes; the schema makes a half-closed trade unrepresentable.
 *
 * {@code pnl} and {@code pnlPercent} are the result against whatever reference price
 * this response has: the exit price for a closed trade, the chart's live price for an
 * open trade inside the account block. An open trade anywhere else has <b>null</b>
 * here -- {@code GET /api/trades} never reads a live price -- and null is not zero.
 * {@code pnlPercent} is the return on the margin, and a loss never goes past -100%.
 *
 * {@code entryPrice} and {@code exitPrice} are the prices the trade actually filled
 * at, which can differ slightly from what was on screen when the button was pressed;
 * the page shows these. {@code openedAt} and {@code closedAt} are real zoned instants
 * -- do not append a {@code Z}.
 */
public record TradeResponse(Long id,
                            String symbol,
                            String direction,
                            BigDecimal quantity,
                            BigDecimal entryPrice,
                            Instant openedAt,
                            BigDecimal exitPrice,
                            Instant closedAt,
                            BigDecimal pnl,
                            BigDecimal pnlPercent) {
}
