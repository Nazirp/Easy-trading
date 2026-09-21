package com.easytrading.backend.trading.dto;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/trades}.
 *
 * <b>There is no price field, and that is the point.</b> The server executes at its
 * own last known price; a {@code price} sent by a client is ignored (Spring Boot does
 * not fail on unknown properties, so an old frontend sending one keeps working rather
 * than breaking -- it simply has no effect). See {@code TradeService} for why: a
 * request body is whatever the caller chooses to type.
 *
 * {@code side} is a String rather than the {@link com.easytrading.backend.trading.TradeSide}
 * enum so that a bad value produces our own {@code INVALID_BODY} message naming the
 * two that are allowed, instead of Jackson's deserialisation error.
 *
 * {@code quantity} is a {@code BigDecimal} and never a double. It arrives as a JSON
 * number and Jackson maps it exactly; a double would already have lost precision
 * before any of our code saw it.
 */
public record PlaceTradeRequest(String symbol, String side, BigDecimal quantity) {
}
