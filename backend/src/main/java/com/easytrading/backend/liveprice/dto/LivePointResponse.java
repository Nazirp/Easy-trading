package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One backfilled point on the demo-trading chart: a 1-minute candle from Twelve
 * Data.
 *
 * <b>`datetime` has no zone and is UTC</b>, exactly as `/api/getPrice` returns for
 * 2h/4h candles (backend/CONTRACTS.md §2). The frontend must append a `Z` before
 * parsing it. Note the contrast with the live candles from
 * {@code /api/getLiveChart}, whose `start` is a real zoned instant and must
 * <i>not</i> get a `Z` appended — different field name, different type, on
 * purpose, so neither has to be remembered.
 *
 * <b>`price` and `close` are the same number.</b> `price` came first, when the
 * live half of the chart was a line and only the close was needed; `open`,
 * `high`, `low` and `close` were added in SCRUM-75, when the live half became
 * candles and a backfill reduced to closes would have made the two halves
 * different kinds of picture on one axis. `price` is kept so the change is purely
 * additive and nothing that already reads it breaks — it should be treated as
 * deprecated, and dropped once the chart draws candles throughout.
 */
public record LivePointResponse(LocalDateTime datetime,
                                BigDecimal price,
                                BigDecimal open,
                                BigDecimal high,
                                BigDecimal low,
                                BigDecimal close) {}
