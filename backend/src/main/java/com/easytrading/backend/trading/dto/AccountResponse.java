package com.easytrading.backend.trading.dto;

import java.math.BigDecimal;

/**
 * The account block: cash, position and live P&amp;L.
 *
 * It appears in two places -- inside {@code /api/getLiveChart} (so the P&amp;L moves
 * on every tick without a second poll) and in the 201 from {@code POST /api/trades}
 * (so the page does not show a stale balance for up to a second after a trade).
 *
 * <b>The whole block is null for a user who has never traded this instrument.</b>
 * Render "no open position" rather than a zero P&amp;L -- they are different facts.
 *
 * {@code quantity} may be zero with the block present: that is someone who traded and
 * then sold out. Their cash and their history are still theirs.
 *
 * {@code unrealisedPnlPercent} is null when nothing is held, because a percentage of
 * no position is undefined rather than zero.
 */
public record AccountResponse(BigDecimal cash,
                              BigDecimal quantity,
                              BigDecimal averageCost,
                              BigDecimal marketValue,
                              BigDecimal unrealisedPnl,
                              BigDecimal unrealisedPnlPercent) {
}
