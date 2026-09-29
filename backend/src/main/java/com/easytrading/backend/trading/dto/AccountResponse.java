package com.easytrading.backend.trading.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The account block, inside {@code /api/getLiveChart} and in both trade responses.
 * Never null for a logged-in user: someone who has never traded has their full cash,
 * zero margin and no open trades.
 *
 * {@code equity = cash + margin + unrealisedPnl}. {@code realisedPnl + unrealisedPnl}
 * is the account's total P&amp;L, so the page never needs to know the starting
 * balance. {@code openTrades} are oldest first, each with its live {@code pnl}.
 *
 * {@code equity}, {@code unrealisedPnl} and each open {@code pnl} are <b>null</b>
 * when open trades need a price that is not available right now -- the page shows
 * "—" rather than a number nobody measured.
 */
public record AccountResponse(BigDecimal cash,
                              BigDecimal margin,
                              BigDecimal equity,
                              BigDecimal unrealisedPnl,
                              BigDecimal realisedPnl,
                              List<TradeResponse> openTrades) {
}
