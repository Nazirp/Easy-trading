package com.easytrading.backend.liveprice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The RestClient FinnhubLivePriceClient talks through. Mirrors
 * MarketDataClientConfig: the base URL is swapped per environment -- WireMock's
 * local URL in tests, the real Finnhub host otherwise (application.yml) -- so
 * the client never needs to know which one it is talking to.
 *
 * The bean is named `finnhubRestClient` and injected by name, because there is
 * now more than one RestClient in the context (`twelveDataRestClient` is the
 * other). Two providers, two base URLs, two beans.
 */
@Configuration
public class LivePriceClientConfig {

    /**
     * Timeouts are not optional here, unlike on the Twelve Data client.
     * LivePriceService holds a lock across this call so that a burst of polls
     * costs one upstream request; a Finnhub that accepts the connection and then
     * never answers would, without a read timeout, park every request thread
     * behind that lock. Short values are safe because the caller falls back to
     * the cached price -- waiting longer than a poll cycle for a price that is
     * about to be asked for again gains nothing.
     */
    @Bean
    public RestClient finnhubRestClient(@Value("${liveprice.finnhub.base-url}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
