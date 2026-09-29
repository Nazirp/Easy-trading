package com.easytrading.backend.trading;

import java.math.BigDecimal;
import java.util.List;

/**
 * The account as the demo-trading page shows it: what is free, what is tied up, what
 * it is all worth right now, and every open trade with its live result.
 *
 * Computed against a price passed in by the caller rather than read here -- see
 * {@link TradeService#accountFor}. That is what lets one snapshot drive both the chart
 * and the P&amp;L in a single {@code /api/getLiveChart} response.
 *
 * @param cash          free cash -- what a new trade can use
 * @param margin        reserved by open trades, the sum of {@code entryPrice x quantity}
 * @param equity        {@code cash + margin + unrealisedPnl}; <b>null</b> when open
 *                      trades need a price that is not available
 * @param unrealisedPnl sum of the open trades' results; null under the same condition
 * @param realisedPnl   sum of the closed trades' results. With {@code unrealisedPnl}
 *                      it is the account's total P&amp;L, so nothing downstream needs
 *                      to know the starting balance
 * @param openTrades    open trades, oldest first, each valued against the same price
 */
public record AccountView(BigDecimal cash,
                          BigDecimal margin,
                          BigDecimal equity,
                          BigDecimal unrealisedPnl,
                          BigDecimal realisedPnl,
                          List<PricedTrade> openTrades) {
}
