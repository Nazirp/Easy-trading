package com.easytrading.backend.marketdata.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class MarketDataClientConfig {

    /**
     * Base URL is swapped per environment: WireMock's local URL in tests
     * (see PriceIntegrationTest), the real Twelve Data host otherwise
     * (application.yml). TwelveDataMarketDataClient never needs to know
     * which one it's talking to.
     */
    @Bean
    public RestClient twelveDataRestClient(@Value("${marketdata.twelvedata.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
