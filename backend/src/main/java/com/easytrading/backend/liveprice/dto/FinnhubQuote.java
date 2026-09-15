package com.easytrading.backend.liveprice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Raw shape of Finnhub's {@code GET /quote} response, kept separate from our own
 * {@link LivePrice} so a change on their side touches only this mapping class --
 * the same split TwelveDataTimeSeriesResponse makes for the other provider.
 *
 * Finnhub names every field with a single letter: `c` current, `d` change,
 * `dp` change percent, `h` day high, `l` day low, `o` open, `pc` previous close,
 * `t` the quote's UNIX timestamp in SECONDS. Only `c` and `t` are mapped --
 * the rest are day statistics the live chart has no use for, and the unmapped
 * ones are ignored rather than being an error.
 *
 * Unlike Twelve Data, Finnhub returns real JSON numbers, so these are numeric
 * types and not Strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinnhubQuote(@JsonProperty("c") BigDecimal current,
                           @JsonProperty("t") Long timestampSeconds) {}
