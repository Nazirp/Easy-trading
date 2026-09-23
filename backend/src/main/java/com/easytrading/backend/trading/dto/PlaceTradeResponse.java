package com.easytrading.backend.trading.dto;

/**
 * Body of the 201 from {@code POST /api/trades}: the executed trade and the account
 * state it produced, from the one call.
 *
 * Returning both is deliberate. The alternative -- 201 with just the trade, and let
 * the next chart poll refresh the balance -- leaves the page showing a stale cash
 * figure for up to a second after a trade the user themselves just placed, which is
 * precisely the moment they are looking at it.
 */
public record PlaceTradeResponse(TradeResponse trade, AccountResponse account) {
}
