package com.easytrading.backend.liveprice;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
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
import com.easytrading.backend.liveprice.dto.LiveHistoryResponse;
import com.easytrading.backend.liveprice.dto.LivePriceResponse;
import com.easytrading.backend.price.PriceRepository;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * SCRUM-72 — the two demo-trading endpoints over real HTTP, against a real
 * (Testcontainers) Postgres and TWO stubbed providers.
 *
 * Two WireMock servers rather than one, because the feature's whole shape is
 * that the past and the present come from different places: Twelve Data answers
 * {@code /time_series} for the backfill, Finnhub answers {@code /quote} for the
 * live tail. One shared stub would have let a wiring mistake — asking the wrong
 * provider for the wrong thing — pass unnoticed.
 *
 * Accounts are created through /api/signup and the session cookie is replayed by
 * hand, as in WatchlistIntegrationTest: TestRestTemplate keeps no cookies, which
 * is exactly what lets one test act as nobody at all.
 *
 * Note on the cache: LivePriceService is a singleton for the whole class, so its
 * 4-second window survives between test methods. Only
 * {@link #twoPollsInsideTheWindowCostOneUpstreamCall} calls /api/getLivePrice
 * with a valid session, which keeps that method's call count meaningful. The
 * expiry and fallback branches are driven deterministically elsewhere —
 * LivePriceServiceTest and LivePriceFallbackIntegrationTest — rather than by
 * sleeping four seconds here.
 *
 * NOTE: not executed in the sandbox this was authored in — that environment
 * blocks Maven Central, so `mvn test` couldn't run there. Run locally with
 * Docker running: `mvn test`.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveTradingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("easytrading")
            .withUsername("easytrading")
            .withPassword("easytrading")
            .withInitScript("schema.sql");

    @RegisterExtension
    static WireMockExtension twelveData = WireMockExtension.newInstance().build();

    @RegisterExtension
    static WireMockExtension finnhub = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("marketdata.twelvedata.base-url", twelveData::baseUrl);
        registry.add("liveprice.finnhub.base-url", finnhub::baseUrl);
        // SCRUM-74: the trade stream stays down for this suite. It would otherwise
        // open a real socket to Finnhub on any machine with FINNHUB_API_KEY set and
        // feed the service prices WireMock knows nothing about -- at which point
        // twoPollsInsideTheWindowCostOneUpstreamCall would see zero calls and fail
        // for a reason that has nothing to do with the cache. The stream has its own
        // tests; this suite is about the REST path it falls back to.
        registry.add("liveprice.finnhub.stream-enabled", () -> "false");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PriceRepository priceRepository;

    // ---- helpers --------------------------------------------------------

    private static HttpHeaders headers(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            h.add(HttpHeaders.COOKIE, cookie);
        }
        return h;
    }

    /** Registers a user and returns their session cookie. */
    private String signUp(String username) {
        HttpEntity<String> body = new HttpEntity<>(
                "{\"username\":\"" + username + "\",\"password\":\"correct-horse\"}", headers(null));
        ResponseEntity<String> response = restTemplate.postForEntity("/api/signup", body, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("expected a session cookie").isNotNull().isNotEmpty();
        return setCookie.get(0).split(";", 2)[0];
    }

    private <T> ResponseEntity<T> getAs(String cookie, String url, Class<T> type) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(cookie)), type);
    }

    /** Twelve Data answers newest-first, which is what makes the ordering assertion below worth making. */
    private void stubBackfill() {
        twelveData.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("BTC/USD"))
                .withQueryParam("interval", equalTo("1min"))
                .willReturn(okJson("""
                        {
                          "values": [
                            {"datetime": "2026-09-15 12:02:00", "open": "63100.0", "high": "63150.0", "low": "63090.0", "close": "63140.0", "volume": "12"},
                            {"datetime": "2026-09-15 12:01:00", "open": "63080.0", "high": "63120.0", "low": "63070.0", "close": "63100.0", "volume": "9"},
                            {"datetime": "2026-09-15 12:00:00", "open": "63050.0", "high": "63090.0", "low": "63040.0", "close": "63080.0", "volume": "11"}
                          ],
                          "status": "ok"
                        }
                        """)));
    }

    private void stubQuote(String priceJson) {
        finnhub.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", equalTo("BINANCE:BTCUSDT"))
                .willReturn(okJson("{\"c\":" + priceJson + ",\"h\":63500.0,\"l\":62800.0,\"o\":63000.0,\"pc\":62950.0,\"t\":1789480000}")));
    }

    // ---- the backfill ----------------------------------------------------

    @Test
    void backfillReturnsOneMinuteClosesOldestFirst() {
        twelveData.resetAll();
        stubBackfill();
        String cookie = signUp("live-history");

        var response = getAs(cookie, "/api/getLiveHistory?symbol=BTC/USD", LiveHistoryResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var points = response.getBody().points();
        assertThat(points).hasSize(3);
        // Oldest first: a chart is drawn left to right, and the provider's own
        // order is the opposite.
        assertThat(points.get(0).price()).isEqualByComparingTo("63080.0");
        assertThat(points.get(2).price()).isEqualByComparingTo("63140.0");
        assertThat(points.get(0).datetime()).isBefore(points.get(2).datetime());

        // ...and it asked for 1-minute candles, explicitly sized. Omitting
        // outputsize would have got Twelve Data's default of 30 by luck rather
        // than by instruction (SCRUM-62).
        twelveData.verify(getRequestedFor(urlPathEqualTo("/time_series"))
                .withQueryParam("interval", equalTo("1min"))
                .withQueryParam("outputsize", equalTo("30")));
    }

    @Test
    void backfillIsNeverPersisted() {
        twelveData.resetAll();
        stubBackfill();
        String cookie = signUp("live-nopersist");

        getAs(cookie, "/api/getLiveHistory?symbol=BTC/USD", LiveHistoryResponse.class);

        // The single most important assertion in this file. `price_candle`'s
        // CHECK constraint does not allow '1min', so a write here would not even
        // fail quietly — but the rule is that this path never writes at all, and
        // a future refactor routing it through PriceService would break that
        // without breaking anything else.
        assertThat(priceRepository.count()).isZero();
    }

    @Test
    void anUnavailableProviderGivesAnEmptyHistoryRatherThanAnError() {
        twelveData.resetAll();
        twelveData.stubFor(get(urlPathEqualTo("/time_series"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));
        String cookie = signUp("live-nohistory");

        var response = getAs(cookie, "/api/getLiveHistory?symbol=BTC/USD", LiveHistoryResponse.class);

        // UC04 extension 5a: a missing past is a degraded chart, never a blocked
        // page — the frontend shows a note and starts drawing from the present.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().points()).isEmpty();
    }

    @Test
    void anythingOtherThanTheDemoInstrumentIsNotFound() {
        String cookie = signUp("live-wrongsymbol");

        var response = getAs(cookie, "/api/getLiveHistory?symbol=EUR/USD", ApiError.class);

        // Not a silent redirect to BTC/USD: a frontend bug should be visible,
        // not a chart quietly showing something else (UC04 BR6).
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    // ---- the live quote and its cache ------------------------------------

    @Test
    void twoPollsInsideTheWindowCostOneUpstreamCall() {
        finnhub.resetAll();
        stubQuote("63140.00");
        String cookie = signUp("live-price");

        var first = getAs(cookie, "/api/getLivePrice?symbol=BTC/USD", LivePriceResponse.class);
        var second = getAs(cookie, "/api/getLivePrice?symbol=BTC/USD", LivePriceResponse.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().symbol()).isEqualTo("BTC/USD");
        assertThat(first.getBody().price()).isEqualByComparingTo("63140.00");
        assertThat(first.getBody().outdated()).isFalse();
        assertThat(second.getBody().price()).isEqualByComparingTo("63140.00");

        // The reason the whole team can demo at once without being rate-limited.
        finnhub.verify(1, getRequestedFor(urlPathEqualTo("/quote")));
        // And our symbol was translated to Finnhub's spelling on the way out —
        // the constant that replaced instrument.finnhub_symbol on 2026-09-15.
        finnhub.verify(getRequestedFor(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", equalTo("BINANCE:BTCUSDT")));
    }

    // ---- the login gate --------------------------------------------------

    @Test
    void bothEndpointsRequireALogin() {
        var history = getAs(null, "/api/getLiveHistory?symbol=BTC/USD", ApiError.class);
        var price = getAs(null, "/api/getLivePrice?symbol=BTC/USD", ApiError.class);

        assertThat(history.getStatusCode().value()).isEqualTo(401);
        assertThat(history.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
        assertThat(price.getStatusCode().value()).isEqualTo(401);
        assertThat(price.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
    }
}
