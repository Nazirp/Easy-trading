package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Paper contract for MS4 (UC04, demo trading) -- not produced by any implementation in MS3. */
public record LivePrice(String symbol, BigDecimal price, Instant timestamp) {}
