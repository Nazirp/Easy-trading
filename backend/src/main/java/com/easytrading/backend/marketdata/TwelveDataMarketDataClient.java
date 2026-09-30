package com.easytrading.backend.marketdata;

import com.easytrading.backend.marketdata.dto.Candle;
import com.easytrading.backend.marketdata.dto.TwelveDataTimeSeriesResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class TwelveDataMarketDataClient implements MarketDataClient {

    /**
     * Twelve Data caps outputsize at 5000 per request; asking for more is an
     * error rather than a silent clamp, so clamp on our side.
     */
    private static final int MAX_OUTPUT_SIZE = 5000;

    private final RestClient restClient;
    private final String apiKey;

    public TwelveDataMarketDataClient(@Qualifier("twelveDataRestClient") RestClient twelveDataRestClient,
                                       @Value("${marketdata.twelvedata.api-key:}") String apiKey) {
        this.restClient = twelveDataRestClient;
        this.apiKey = apiKey;
    }

    /**
     * outputsize is ALWAYS sent. Omitting it makes Twelve Data return its
     * default of 30 candles, which is fewer than any of our four chart ranges
     * needs.
     *
     * timezone=UTC is ALWAYS sent too. Twelve Data's
     * `timezone` parameter defaults to "Exchange" -- local exchange time --
     * so without it an AAPL intraday candle arrives on New York time and a
     * EUR/USD one on a different clock again. parseDatetime() then strips
     * that context into a naive LocalDateTime, and the stored value silently
     * means a different instant per instrument.
     *
     * Twelve Data IGNORES timezone for 1day/1week (those are always
     * exchange-local), and that is the behaviour we want -- a daily candle is a
     * trading day. So the convention is: intraday = UTC instant, daily/weekly =
     * exchange trading date. Written up in backend/CONTRACTS.md.
     */
    @Override
    public List<Candle> getCandles(String symbol, String interval, int outputSize) {
        int requested = Math.max(1, Math.min(outputSize, MAX_OUTPUT_SIZE));

        TwelveDataTimeSeriesResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/time_series")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("outputsize", requested)
                        .queryParam("timezone", "UTC")
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
     * weekly candles come back as "2026-08-22", intraday (2h, 4h) as
     * "2026-08-22 12:00:00". Both are normalized to LocalDateTime here so the
     * rest of the app never has to care which interval it's holding.
     *
     * Neither form carries a zone, so the zone has to be guaranteed by the
     * request rather than recovered here: intraday values are UTC because
     * getCandles sends timezone=UTC, daily/weekly are the exchange's trading
     * date. Do not "fix up" the value in this method -- it has no way to know
     * which exchange the symbol belongs to.
     */
    private LocalDateTime parseDatetime(String raw) {
        if (raw.length() <= 10) {
            return LocalDate.parse(raw).atStartOfDay();
        }
        return LocalDateTime.parse(raw.replace(' ', 'T'));
    }
}
