package com.easytrading.backend.liveprice;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.easytrading.backend.common.ApiError;
import com.easytrading.backend.liveprice.dto.LivePriceResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * SCRUM-72, UC04 extensions 6a/6b — what {@code GET /api/getLivePrice} answers
 * when Finnhub is down, over real HTTP.
 *
 * Its own class, and its own Spring context, for one reason: it sets
 * {@code liveprice.cache-ttl} to zero so that every request takes the "entry
 * expired, go upstream" branch. That makes the fallback reachable immediately
 * instead of four seconds later, which is why this file contains no sleeps and
 * cannot flake on a loaded machine. LiveTradingIntegrationTest keeps the real
 * 4-second window, because proving the cache actually caches needs it.
 *
 * The methods are ORDERED, which is normally a smell and here is the point: the
 * service's cache is one field on one singleton, so "the cache is cold" is a
 * state that only exists until the first successful poll. The two cold-cache
 * cases therefore have to run before the one that warms it. The alternative — a
 * reset method on the service, existing only for tests — would put test
 * machinery into production code to avoid three annotations.
 *
 * NOTE: not executed in the sandbox this was authored in — run locally with
 * Docker running: `mvn test`.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LivePriceFallbackIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("easytrading")
            .withUsername("easytrading")
            .withPassword("easytrading")
            .withInitScript("schema.sql");

    @RegisterExtension
    static WireMockExtension finnhub = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("liveprice.finnhub.base-url", finnhub::baseUrl);
        // Every entry is already expired -> every request goes upstream.
        registry.add("liveprice.cache-ttl", () -> "0s");
    }

    @Autowired
    TestRestTemplate restTemplate;

    private static HttpHeaders headers(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            h.add(HttpHeaders.COOKIE, cookie);
        }
        return h;
    }

    private String signUp(String username) {
        HttpEntity<String> body = new HttpEntity<>(
                "{\"username\":\"" + username + "\",\"password\":\"correct-horse\"}", headers(null));
        ResponseEntity<String> response = restTemplate.postForEntity("/api/signup", body, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull().isNotEmpty();
        return setCookie.get(0).split(";", 2)[0];
    }

    private <T> ResponseEntity<T> getAs(String cookie, Class<T> type) {
        return restTemplate.exchange("/api/getLivePrice?symbol=BTC/USD", HttpMethod.GET,
                new HttpEntity<>(headers(cookie)), type);
    }

    @Test
    @Order(1)
    void aColdCacheAndAFailingProviderIs503() {
        finnhub.resetAll();
        finnhub.stubFor(get(urlPathEqualTo("/quote")).willReturn(serverError()));
        String cookie = signUp("fallback-cold");

        var response = getAs(cookie, ApiError.class);

        // 503 and not 500: this application is fine, the provider is not.
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("LIVE_PRICE_UNAVAILABLE");
    }

    @Test
    @Order(3)
    void aWarmCacheSurvivesAFailingProvider() {
        finnhub.resetAll();
        finnhub.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", equalTo("BINANCE:BTCUSDT"))
                .willReturn(okJson("{\"c\":63140.00,\"t\":1789480000}")));
        String cookie = signUp("fallback-warm");

        var good = getAs(cookie, LivePriceResponse.class);
        assertThat(good.getStatusCode().value()).isEqualTo(200);
        assertThat(good.getBody().outdated()).isFalse();

        // Finnhub falls over between polls.
        finnhub.resetAll();
        finnhub.stubFor(get(urlPathEqualTo("/quote")).willReturn(serverError()));

        var degraded = getAs(cookie, LivePriceResponse.class);

        // Still 200, still the last known price, and honest about it — the page
        // keeps drawing and shows a "may be outdated" note (UC04 6a/6b).
        assertThat(degraded.getStatusCode().value()).isEqualTo(200);
        assertThat(degraded.getBody().price()).isEqualByComparingTo("63140.00");
        assertThat(degraded.getBody().outdated()).isTrue();
    }

    @Test
    @Order(2)
    void aZeroPriceCountsAsAFailedCallRatherThanAPriceOfZero() {
        finnhub.resetAll();
        // Finnhub's answer for a symbol it has no data on: 200, with c = 0.
        finnhub.stubFor(get(urlPathEqualTo("/quote")).willReturn(okJson("{\"c\":0,\"t\":0}")));
        String cookie = signUp("fallback-zero");

        var response = getAs(cookie, ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("LIVE_PRICE_UNAVAILABLE");
    }
}
