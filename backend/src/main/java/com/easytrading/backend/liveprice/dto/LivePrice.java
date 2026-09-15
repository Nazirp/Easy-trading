package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One live price point from {@code LivePriceClient} -- the provider-neutral
 * shape the rest of the application sees, produced by FinnhubLivePriceClient
 * since SCRUM-72.
 *
 * `symbol` is OUR spelling (BTC/USD), not Finnhub's, so nothing downstream of
 * the client has to know that the provider calls it BINANCE:BTCUSDT.
 *
 * An Instant rather than a LocalDateTime because this is a moment in time, not a
 * slot in a series: it is the instant the price was true, and it is rendered by
 * the browser in the user's own clock. Candle datetimes are the other case and
 * are LocalDateTime -- see backend/CONTRACTS.md section 2 on the timezone
 * convention.
 */
public record LivePrice(String symbol, BigDecimal price, Instant timestamp) {}
