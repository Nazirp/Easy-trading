package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.FinnhubQuote;
import com.easytrading.backend.liveprice.dto.LivePrice;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@link LivePriceClient} over Finnhub's REST quote endpoint (SCRUM-72).
 *
 * {@code GET /quote?symbol={finnhubSymbol}&token={key}} -- the REST endpoint,
 * deliberately NOT the trade-tick WebSocket. A WebSocket only speaks when a
 * trade happens, so a thin pair can go silent for minutes; a graded demo cannot
 * depend on the market being busy at that moment. REST polling always answers.
 *
 * This class is a mapper and nothing else: no caching, no fallback, no retry.
 * Those live in {@link LivePriceService}, one layer up, so that this file stays
 * the single place that knows what Finnhub's wire format looks like -- the same
 * division TwelveDataMarketDataClient has with PriceService.
 */
@Component
public class FinnhubLivePriceClient implements LivePriceClient {

    private final RestClient restClient;
    private final String apiKey;

    /**
     * The @Qualifier is required, not decoration: there are two RestClient beans
     * in the context now (this one and Twelve Data's), so injecting by type
     * alone is ambiguous.
     */
    public FinnhubLivePriceClient(@Qualifier("finnhubRestClient") RestClient finnhubRestClient,
                                  @Value("${liveprice.finnhub.api-key:}") String apiKey) {
        this.restClient = finnhubRestClient;
        this.apiKey = apiKey;
    }

    @Override
    public LivePrice getLivePrice(String finnhubSymbol) {
        FinnhubQuote quote = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/quote")
                        .queryParam("symbol", finnhubSymbol)
                        .queryParam("token", apiKey)
                        .build())
                .retrieve()
                .body(FinnhubQuote.class);

        // Finnhub answers 200 with {"c":0,...} for an unknown symbol and for a
        // symbol it has no data on -- there is no 404. A price of zero is
        // therefore an error dressed as a success, and must be treated as a
        // failed call rather than plotted as a real price of $0.
        if (quote == null || quote.current() == null || quote.current().compareTo(BigDecimal.ZERO) <= 0) {
            throw new LivePriceUnavailableException(
                    "Finnhub returned no usable price for " + finnhubSymbol + ".");
        }

        return new LivePrice(DemoInstrument.SYMBOL, quote.current(), timestampOf(quote));
    }

    /**
     * Finnhub's `t` is a UNIX timestamp in seconds, and is 0 or absent on some
     * responses. Falling back to now is correct rather than lazy: we have just
     * asked for the current price and been given one, so the moment it was true
     * is, to the precision this chart plots at, now.
     */
    private static Instant timestampOf(FinnhubQuote quote) {
        Long seconds = quote.timestampSeconds();
        if (seconds == null || seconds <= 0) {
            return Instant.now();
        }
        return Instant.ofEpochSecond(seconds);
    }
}
