package com.easytrading.backend.price.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One candle. `datetime` rather than `date` because 4h candles need the time of
 * day — for 1day and 1week it's simply midnight. Serialized ISO-8601
 * ("2026-08-22T00:00:00").
 */
public record PriceResponse(LocalDateTime datetime, BigDecimal open, BigDecimal high, BigDecimal low,
                            BigDecimal close, Long volume) {}
