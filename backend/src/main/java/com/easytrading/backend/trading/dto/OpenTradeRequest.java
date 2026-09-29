package com.easytrading.backend.trading.dto;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/trades}.
 *
 * <b>There is no price field, and that is the point.</b> The server fills at its own
 * last known price; a {@code price} sent by a client is ignored (unknown properties do
 * not fail, so an old frontend that still sends one keeps working -- it simply has no
 * effect). See {@code TradeService} for why: a request body is whatever the caller
 * chooses to type.
 *
 * {@code direction} is a String rather than the enum so a bad value produces our own
 * {@code INVALID_BODY} message naming the two that are allowed, instead of Jackson's
 * deserialisation error. {@code quantity} is a {@code BigDecimal}, never a double: a
 * double would have lost precision before any of our code saw it.
 */
public record OpenTradeRequest(String symbol, String direction, BigDecimal quantity) {
}
