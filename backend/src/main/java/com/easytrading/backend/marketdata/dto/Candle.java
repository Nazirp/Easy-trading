package com.easytrading.backend.marketdata.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One candle as returned by MarketDataClient. Fields match the `price_candle`
 * table's columns (db/schema.sql). `datetime` rather than a date because 4h
 * candles carry a time of day; daily/weekly candles land on midnight.
 */
public record Candle(LocalDateTime datetime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                     Long volume) {}
