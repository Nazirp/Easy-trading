package com.easytrading.backend.marketdata.dto;

import com.easytrading.backend.instrument.InstrumentType;

/**
 * What MarketDataClient.searchInstruments will return, already normalized
 * to our BR1 types. Paper contract for MS3 -- see MarketDataClient.
 */
public record InstrumentMatch(String symbol, String name, String exchange, InstrumentType type) {}
