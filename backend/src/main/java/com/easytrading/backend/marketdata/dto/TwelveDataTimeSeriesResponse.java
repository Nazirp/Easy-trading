package com.easytrading.backend.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Raw shape of Twelve Data's GET /time_series response (daily interval).
 * Kept separate from our own Candle so a change on their side only
 * touches this mapping class, never the contract the rest of the app is
 * built against. Twelve Data returns numeric fields as strings, hence
 * everything here is a String and gets parsed in TwelveDataMarketDataClient.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TwelveDataTimeSeriesResponse(List<Item> values) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String datetime, String open, String high, String low, String close, String volume) {}
}
