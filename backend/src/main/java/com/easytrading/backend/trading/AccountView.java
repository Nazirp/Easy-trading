package com.easytrading.backend.trading;

import java.math.BigDecimal;

/**
 * The account as the demo-trading page shows it: what the user has, what it is worth
 * right now, and whether they are up or down.
 *
 * Computed against a price passed in by the caller rather than read here -- see
 * {@link TradeService#accountFor}. That is what lets the same snapshot drive both the
 * chart and the P&amp;L in one {@code /api/getLiveChart} response.
 *
 * @param cash                 the virtual balance, scale 5
 * @param quantity             units held, scale 8; may be zero for someone who traded and sold out
 * @param averageCost          weighted average price paid, scale 5; zero when nothing is held
 * @param marketValue          {@code quantity x current price}
 * @param unrealisedPnl        {@code marketValue - (averageCost x quantity)}; what closing now would realise
 * @param unrealisedPnlPercent the same as a percentage, or <b>null</b> when nothing is
 *                             held -- a percentage of no position is undefined, not zero,
 *                             and rendering "0.00%" would claim a break-even that does
 *                             not exist
 */
public record AccountView(BigDecimal cash,
                          BigDecimal quantity,
                          BigDecimal averageCost,
                          BigDecimal marketValue,
                          BigDecimal unrealisedPnl,
                          BigDecimal unrealisedPnlPercent) {
}
