package com.easytrading.backend.marketdata;

import com.easytrading.backend.marketdata.dto.Candle;
import com.easytrading.backend.marketdata.dto.InstrumentMatch;
import com.easytrading.backend.marketdata.dto.Quote;
import com.easytrading.backend.marketdata.dto.TwelveDataTimeSeriesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class TwelveDataMarketDataClient implements MarketDataClient {

    private final RestClient restClient;
    private final String apiKey;

    public TwelveDataMarketDataClient(RestClient twelveDataRestClient,
                                       @Value("${marketdata.twelvedata.api-key:}") String apiKey) {
        this.restClient = twelveDataRestClient;
        this.apiKey = apiKey;
    }

    @Override
    public List<InstrumentMatch> searchInstruments(String query) {
        throw new UnsupportedOperationException("searchInstruments is a paper contract for MS3 — search is DB-only");
    }

    @Override
    public Quote getQuote(String symbol) {
        throw new UnsupportedOperationException("getQuote is a paper contract for MS3 — nothing needs it yet");
    }

    @Override
    public List<Candle> getCandles(String symbol, String interval) {
        TwelveDataTimeSeriesResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/time_series")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("apikey", apiKey)
                        .build())
                .retrieve()
                .body(TwelveDataTimeSeriesResponse.class);

        if (response == null || response.values() == null) {
            return List.of();
        }

        return response.values().stream()
                .map(this::toCandle)
                .toList();
    }

    private Candle toCandle(TwelveDataTimeSeriesResponse.Item item) {
        return new Candle(
                parseDatetime(item.datetime()),
                new BigDecimal(item.open()),
                new BigDecimal(item.high()),
                new BigDecimal(item.low()),
                new BigDecimal(item.close()),
                item.volume() == null || item.volume().isBlank() ? null : Long.parseLong(item.volume()));
    }

    /**
     * Twelve Data formats the datetime differently per interval: daily and
     * weekly candles come back as "2026-08-22", intraday (4h) as
     * "2026-08-22 12:00:00". Both are normalized to LocalDateTime here so the
     * rest of the app never has to care which interval it's holding.
     */
    private LocalDateTime parseDatetime(String raw) {
        if (raw.length() <= 10) {
            return LocalDate.parse(raw).atStartOfDay();
        }
        return LocalDateTime.parse(raw.replace(' ', 'T'));
    }
}
