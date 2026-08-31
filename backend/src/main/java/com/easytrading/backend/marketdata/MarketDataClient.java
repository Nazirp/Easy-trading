package com.easytrading.backend.marketdata;

import com.easytrading.backend.marketdata.dto.Candle;
import com.easytrading.backend.marketdata.dto.InstrumentMatch;
import com.easytrading.backend.marketdata.dto.Quote;

import java.util.List;

/**
 * Backend <-> Twelve Data (SCRUM-36).
 *
 * Scope as of 2026-08-23: the search endpoint is DB-only (see
 * InstrumentSearchService) and never calls searchInstruments. getCandles
 * IS real and wired -- PriceService calls it on demand when a selected
 * instrument has no price history cached yet (UC01 step 10). getQuote
 * stays a paper contract, nothing needs a single live quote in MS3.
 */
public interface MarketDataClient {

    /** Paper contract -- not called anywhere in MS3 (search is DB-only). */
    List<InstrumentMatch> searchInstruments(String query);

    /** Paper contract -- not called anywhere in MS3. */
    Quote getQuote(String symbol);

    /**
     * Real and wired: candles for a symbol at the requested interval, called by
     * PriceService on a cache miss.
     *
     * outputSize is how many candles to ask the provider for. It must be passed
     * explicitly because Twelve Data defaults to 30 when the parameter is
     * omitted, which is short of every chart range we serve (SCRUM-62).
     */
    List<Candle> getCandles(String symbol, String interval, int outputSize);
}
