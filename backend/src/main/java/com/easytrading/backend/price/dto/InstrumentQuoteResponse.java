package com.easytrading.backend.price.dto;

import java.math.BigDecimal;

/**
 * One row of the instrument browser (GET /api/instruments): what an instrument
 * IS, plus what it last did.
 *
 * {@code lastPrice} and {@code changePercent} are NULLABLE, and that is a
 * contract, not an oversight. They are filled from candles already in the local
 * cache and from nothing else -- an instrument nobody has charted yet has no
 * cached candles, so it lists with a name and a type and no numbers.
 *
 * changePercent is the move from the previous daily close to the latest one,
 * already in percent and rounded to 2dp -- the frontend renders it, it does not
 * compute it.
 */
public record InstrumentQuoteResponse(
        String symbol,
        String name,
        String type,
        BigDecimal lastPrice,
        BigDecimal changePercent) {}
