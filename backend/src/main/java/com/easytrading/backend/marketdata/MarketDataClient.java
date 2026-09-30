package com.easytrading.backend.marketdata;

import com.easytrading.backend.marketdata.dto.Candle;

import java.util.List;

/**
 * Backend <-> Twelve Data.
 */
public interface MarketDataClient {

    /**
     * Candles for a symbol at the requested interval, called by PriceService on
     * a cache miss.
     *
     * outputSize is how many candles to ask the provider for. It must be passed
     * explicitly because Twelve Data defaults to 30 when the parameter is
     * omitted, which is short of every chart range we serve.
     */
    List<Candle> getCandles(String symbol, String interval, int outputSize);
}
