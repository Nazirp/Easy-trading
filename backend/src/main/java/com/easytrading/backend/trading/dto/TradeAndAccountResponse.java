package com.easytrading.backend.trading.dto;

/**
 * What opening or closing a trade returns: the trade as it now stands, and the account
 * it left behind. The account comes back too so the page shows the new balance at once
 * instead of waiting up to a second for the next chart poll.
 */
public record TradeAndAccountResponse(TradeResponse trade, AccountResponse account) {
}
