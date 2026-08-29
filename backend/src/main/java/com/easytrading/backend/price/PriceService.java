package com.easytrading.backend.price;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * UC01 step 10: once the user has selected an instrument from a prior /search,
 * get its price history at the requested interval — from the DB if we have it,
 * otherwise ingest it from Twelve Data on demand and persist it, so the next
 * request for the same symbol+interval is a cache hit.
 *
 * Deliberately a simple "empty vs non-empty" cache check for now, not the
 * staleness-aware MISSING/INSUFFICIENT/OK distinction that check_price_data()
 * in db/schema.sql already models — re-ingesting stale data is a real follow-up,
 * just not wired into this on-demand path yet.
 */
@Service
public class PriceService {

    private final PriceRepository priceRepository;
    private final InstrumentRepository instrumentRepository;
    private final MarketDataClient marketDataClient;

    public PriceService(PriceRepository priceRepository, InstrumentRepository instrumentRepository,
                         MarketDataClient marketDataClient) {
        this.priceRepository = priceRepository;
        this.instrumentRepository = instrumentRepository;
        this.marketDataClient = marketDataClient;
    }

    public List<Price> getPrices(String symbol, String rawInterval) {
        Interval interval = Interval.fromCode(rawInterval == null ? "" : rawInterval.trim())
                .orElseThrow(() -> new InvalidIntervalException(
                        "Unknown interval '" + rawInterval + "'. Expected one of: " + Interval.supportedCodes() + "."));

        List<Price> cached = priceRepository.findBySymbolAndIntervalOrderByDatetime(symbol, interval.code());
        if (!cached.isEmpty()) {
            return cached;
        }

        if (!instrumentRepository.existsById(symbol)) {
            // same "not found" signal the search endpoint uses
            throw new InstrumentNotFoundException("No instrument found for '" + symbol + "'.");
        }

        // Instrument is real, we just have no candles for it at this interval yet -> ingest now.
        List<Candle> candles = marketDataClient.getCandles(symbol, interval.code());
        return candles.stream()
                .map(candle -> priceRepository.save(toPrice(symbol, interval, candle)))
                .toList();
    }

    private Price toPrice(String symbol, Interval interval, Candle candle) {
        return new Price(symbol, interval.code(), candle.datetime(),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }
}
