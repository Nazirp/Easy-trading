package com.easytrading.backend.marketdata.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Paper contract for MS4 -- not produced by any implementation yet in MS3. */
public record Quote(String symbol, BigDecimal price, Instant timestamp) {}
