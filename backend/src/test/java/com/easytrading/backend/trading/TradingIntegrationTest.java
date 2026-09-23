package com.easytrading.backend.trading;

import com.easytrading.backend.common.ApiError;
import com.easytrading.backend.liveprice.dto.LiveChartResponse;
import com.easytrading.backend.trading.dto.PlaceTradeResponse;
import com.easytrading.backend.trading.dto.TradeHistoryResponse;
import com.easytrading.backend.user.UserRepository;
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

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-79 — placing simulated trades over real HTTP, against a real Postgres.
 *
 * {@code TradeServiceTest} already covers the arithmetic without a container, so this
 * suite deliberately does not repeat it. What it tests is the part a unit test cannot:
 * that the {@code trade} row and the new {@code cash_balance} actually land together
 * in one transaction, that the JPA mapping matches the columns Glenn wrote (a
 * mismatch is a startup failure, not a subtle bug), and that the account block reaches
 * the chart response over the wire.
 *
 * The trade stream stays disabled for the same reason as the other liveprice suites —
 * it would otherwise open a real socket on any machine with a Finnhub key and feed
 * prices WireMock knows nothing about.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TradingIntegrationTest {

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
        registry.add("liveprice.finnhub.stream-enabled", () -> "false");
        // The Spring context is shared across test methods, so a price cached by an
        // earlier test would still be inside the default 6-second window when a later
        // one stubs a different answer -- and anUnusablePriceIsA503AndNotAFill would
        // quietly pass on a stale 1000.0 instead of exercising the branch it names.
        // Zero makes every call go upstream, the same setting LivePriceFallbackIntegrationTest uses.
        registry.add("liveprice.max-price-age", () -> "0s");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    TradeRepository tradeRepository;

    @Autowired
    UserRepository userRepository;

    // ---- helpers --------------------------------------------------------

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
        assertThat(setCookie).as("expected a session cookie").isNotNull().isNotEmpty();
        return setCookie.get(0).split(";", 2)[0];
    }

    private <T> ResponseEntity<T> post(String cookie, String url, String json, Class<T> type) {
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(json, headers(cookie)), type);
    }

    private <T> ResponseEntity<T> getAs(String cookie, String url, Class<T> type) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(cookie)), type);
    }

    private void stubQuote(String close) {
        finnhub.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", equalTo("BINANCE:BTCUSDT"))
                .willReturn(okJson("{\"c\":" + close + ",\"h\":1,\"l\":1,\"o\":1,\"pc\":1,\"t\":1789480000}")));
    }

    private void stubBackfill() {
        twelveData.stubFor(get(urlPathEqualTo("/time_series"))
                .willReturn(okJson("""
                        {"values":[{"datetime":"2026-09-15 12:00:00","open":"1","high":"1","low":"1","close":"1","volume":"1"}],
                         "status":"ok"}
                        """)));
    }

    // ---- the thing a unit test cannot prove ------------------------------

    @Test
    void aBuyWritesTheTradeAndTheNewBalanceTogether() {
        stubQuote("1000.0");
        String cookie = signUp("trader-buy");

        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":0.5}", PlaceTradeResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().trade().price()).isEqualByComparingTo("1000.0");
        assertThat(response.getBody().account().cash()).isEqualByComparingTo("9500.00000");

        // Both halves, read back from the database rather than from the response.
        var user = userRepository.findByUsername("trader-buy").orElseThrow();
        assertThat(user.getCashBalance()).isEqualByComparingTo("9500.00000");
        assertThat(tradeRepository.findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(user.getId(), "BTC/USD"))
                .hasSize(1);
    }

    @Test
    void theEntityMappingMatchesTheColumnsAndSurvivesARoundTrip() {
        stubQuote("76391.4");
        String cookie = signUp("trader-mapping");

        post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":0.00131579}", PlaceTradeResponse.class);

        var user = userRepository.findByUsername("trader-mapping").orElseThrow();
        Trade stored = tradeRepository
                .findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(user.getId(), "BTC/USD").get(0);

        assertThat(stored.getSide()).isEqualTo(TradeSide.BUY);
        // Eight decimal places survive. At the money scale of five this would have
        // come back as 0.00132 -- which is the entire reason for the wider column.
        assertThat(stored.getQuantity()).isEqualByComparingTo("0.00131579");
        assertThat(stored.getPrice()).isEqualByComparingTo("76391.40000");
        assertThat(stored.getExecutedAt()).isNotNull();
    }

    @Test
    void aPriceInTheRequestBodyIsIgnored() {
        stubQuote("1000.0");
        String cookie = signUp("trader-price");

        // The classic attack, and the classic accident: a stale tab posting an old
        // price. Neither has any effect, because nothing reads this field.
        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":1,\"price\":1}",
                PlaceTradeResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().trade().price()).isEqualByComparingTo("1000.0");
        assertThat(response.getBody().account().cash()).isEqualByComparingTo("9000.00000");
    }

    // ---- refusals --------------------------------------------------------

    @Test
    void buyingBeyondTheBalanceIsA409AndChangesNothing() {
        stubQuote("1000.0");
        String cookie = signUp("trader-broke");

        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":11}", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_FUNDS");

        var user = userRepository.findByUsername("trader-broke").orElseThrow();
        assertThat(user.getCashBalance()).isEqualByComparingTo(new BigDecimal("10000.00000"));
        assertThat(tradeRepository.findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(user.getId(), "BTC/USD"))
                .isEmpty();
    }

    @Test
    void sellingMoreThanHeldIsA409() {
        stubQuote("1000.0");
        String cookie = signUp("trader-short");

        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"SELL\",\"quantity\":1}", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_POSITION");
    }

    @Test
    void anUnusablePriceIsA503AndNotAFill() {
        // Finnhub answers 200 with c:0 for a symbol it has no data for. Treated as a
        // failure rather than a price -- a zero fill would be a permanent wrong number.
        stubQuote("0");
        String cookie = signUp("trader-noprice");

        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":1}", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("LIVE_PRICE_UNAVAILABLE");

        var user = userRepository.findByUsername("trader-noprice").orElseThrow();
        assertThat(tradeRepository.findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(user.getId(), "BTC/USD"))
                .isEmpty();
    }

    @Test
    void anythingButTheDemoInstrumentIsA404() {
        stubQuote("1000.0");
        String cookie = signUp("trader-wrongsymbol");

        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"EUR/USD\",\"side\":\"BUY\",\"quantity\":1}", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void aMalformedQuantityIsA400() {
        stubQuote("1000.0");
        String cookie = signUp("trader-badqty");

        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":0}", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_BODY");
    }

    // ---- history, and the account on the chart ---------------------------

    @Test
    void historyIsThisUsersTradesNewestFirstAndNobodyElses() {
        stubQuote("1000.0");
        String mine = signUp("trader-mine");
        String theirs = signUp("trader-theirs");

        post(mine, "/api/trades", "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":1}", String.class);
        post(mine, "/api/trades", "{\"symbol\":\"BTC/USD\",\"side\":\"SELL\",\"quantity\":1}", String.class);
        post(theirs, "/api/trades", "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":2}", String.class);

        var response = getAs(mine, "/api/trades?symbol=BTC/USD", TradeHistoryResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().trades()).hasSize(2);
        assertThat(response.getBody().trades().get(0).side()).isEqualTo("SELL");
        assertThat(response.getBody().trades().get(0).executedAt()).isNotNull();
    }

    @Test
    void theChartCarriesTheAccountBlockOnlyOnceTheUserHasTraded() {
        stubBackfill();
        stubQuote("1000.0");
        String cookie = signUp("trader-chart");

        var before = getAs(cookie, "/api/getLiveChart?symbol=BTC/USD", LiveChartResponse.class);
        assertThat(before.getStatusCode().value()).isEqualTo(200);
        // Never traded: no block at all, so the page says "no open position" rather
        // than reporting a P&L of zero on a holding that does not exist.
        assertThat(before.getBody().account()).isNull();

        post(cookie, "/api/trades", "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":1}", String.class);

        var after = getAs(cookie, "/api/getLiveChart?symbol=BTC/USD", LiveChartResponse.class);
        assertThat(after.getBody().account()).isNotNull();
        assertThat(after.getBody().account().cash()).isEqualByComparingTo("9000.00000");
        assertThat(after.getBody().account().quantity()).isEqualByComparingTo("1");
    }

    @Test
    void bothEndpointsAre401WhenLoggedOut() {
        var place = post(null, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":1}", ApiError.class);
        var history = getAs(null, "/api/trades?symbol=BTC/USD", ApiError.class);

        assertThat(place.getStatusCode().value()).isEqualTo(401);
        assertThat(place.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
        assertThat(history.getStatusCode().value()).isEqualTo(401);
        assertThat(history.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
    }
}
