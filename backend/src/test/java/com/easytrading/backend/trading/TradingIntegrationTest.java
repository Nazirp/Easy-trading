package com.easytrading.backend.trading;

import com.easytrading.backend.common.ApiError;
import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.instrument.InstrumentType;
import com.easytrading.backend.liveprice.dto.LiveChartResponse;
import com.easytrading.backend.trading.dto.TradeAndAccountResponse;
import com.easytrading.backend.trading.dto.TradeHistoryResponse;
import com.easytrading.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opening and closing CFD trades over real HTTP, against a real Postgres.
 *
 * {@code TradeTest} and {@code TradeServiceTest} cover the arithmetic and the rules
 * without a container, so this suite does not repeat them. It tests what they cannot:
 * that the trade row and the cash land together in one transaction; that the JPA
 * mapping matches the columns (a mismatch is a startup failure, not a subtle bug);
 * that the account block reaches the chart response; and above all that <b>two
 * simultaneous closes of one trade credit the account once</b> — a property of the
 * database's row lock, which no mock can show.
 *
 * The trade stream stays disabled for the same reason as the other liveprice suites,
 * and {@code liveprice.max-price-age} is zero so every call takes the stubbed REST
 * quote: the Spring context is shared across test methods, and a price cached by an
 * earlier one would otherwise still be current when a later one stubs a different
 * answer.
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
        registry.add("liveprice.max-price-age", () -> "0s");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    TradeRepository tradeRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InstrumentRepository instrumentRepository;

    /** A trade's symbol references the instrument table, and the test database starts empty. */
    @BeforeEach
    void givenBitcoinExists() {
        if (!instrumentRepository.existsById("BTC/USD")) {
            instrumentRepository.save(
                    new Instrument("BTC/USD", "Bitcoin / US Dollar", null, InstrumentType.CRYPTO));
        }
    }

    // ---- the round trip ----------------------------------------------------------

    @Test
    void openingThenClosingMovesTheCashByExactlyTheResult() {
        stubQuote("1000.0");
        String cookie = signUp("cfd-roundtrip");

        var opened = open(cookie, "LONG", "1", TradeAndAccountResponse.class);
        assertThat(opened.getStatusCode().value()).isEqualTo(201);
        assertThat(opened.getBody().trade().entryPrice()).isEqualByComparingTo("1000");
        assertThat(opened.getBody().trade().pnl()).isNull();
        assertThat(opened.getBody().account().cash()).isEqualByComparingTo("9000");
        assertThat(opened.getBody().account().margin()).isEqualByComparingTo("1000");

        stubQuote("1100.0");
        var closed = close(cookie, opened.getBody().trade().id(), TradeAndAccountResponse.class);

        assertThat(closed.getStatusCode().value()).isEqualTo(200);
        assertThat(closed.getBody().trade().exitPrice()).isEqualByComparingTo("1100");
        assertThat(closed.getBody().trade().closedAt()).isNotNull();
        assertThat(closed.getBody().trade().pnl()).isEqualByComparingTo("100");
        assertThat(closed.getBody().account().cash()).isEqualByComparingTo("10100");
        assertThat(closed.getBody().account().margin()).isEqualByComparingTo("0");
        assertThat(closed.getBody().account().realisedPnl()).isEqualByComparingTo("100");

        // Read back from the database, not from the response.
        var user = userRepository.findByUsername("cfd-roundtrip").orElseThrow();
        assertThat(user.getCashBalance()).isEqualByComparingTo("10100");
    }

    @Test
    void aShortProfitsWhenThePriceFalls() {
        stubQuote("1000.0");
        String cookie = signUp("cfd-short");

        var opened = open(cookie, "SHORT", "1", TradeAndAccountResponse.class);
        assertThat(opened.getStatusCode().value()).isEqualTo(201);

        stubQuote("900.0");
        var closed = close(cookie, opened.getBody().trade().id(), TradeAndAccountResponse.class);

        assertThat(closed.getBody().trade().pnl()).isEqualByComparingTo("100");
        assertThat(closed.getBody().account().cash()).isEqualByComparingTo("10100");
    }

    @Test
    void theEntityMappingMatchesTheColumns() {
        stubQuote("76391.4");
        String cookie = signUp("cfd-mapping");

        var opened = open(cookie, "LONG", "0.00131579", TradeAndAccountResponse.class);
        Long id = opened.getBody().trade().id();

        var user = userRepository.findByUsername("cfd-mapping").orElseThrow();
        Trade stored = tradeRepository.findByIdAndUserId(id, user.getId()).orElseThrow();

        assertThat(stored.getDirection()).isEqualTo(TradeDirection.LONG);
        // Eight decimal places survive; at the money scale this would come back 0.00132.
        assertThat(stored.getQuantity()).isEqualByComparingTo("0.00131579");
        assertThat(stored.getEntryPrice()).isEqualByComparingTo("76391.40000");
        assertThat(stored.getOpenedAt()).isNotNull();
        assertThat(stored.getExitPrice()).isNull();
        assertThat(stored.getClosedAt()).isNull();
    }

    @Test
    void aPriceInTheRequestBodyIsIgnored() {
        stubQuote("1000.0");
        String cookie = signUp("cfd-price");

        // The classic attack and the classic accident — neither has any effect,
        // because nothing reads this field.
        var response = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"direction\":\"LONG\",\"quantity\":1,\"price\":1}",
                TradeAndAccountResponse.class);

        assertThat(response.getBody().trade().entryPrice()).isEqualByComparingTo("1000");
        assertThat(response.getBody().account().cash()).isEqualByComparingTo("9000");
    }

    // ---- closing: the cases that matter ------------------------------------------

    @Test
    void twoSimultaneousClosesCreditTheAccountOnce() throws Exception {
        stubQuote("1000.0");
        String cookie = signUp("cfd-doubleclick");
        Long id = open(cookie, "LONG", "1", TradeAndAccountResponse.class).getBody().trade().id();

        // Two closes released at the same instant — a nervous double click, or two tabs.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> statuses = new ArrayList<>();
        Callable<Integer> closeIt = () -> {
            start.await();
            return close(cookie, id, String.class).getStatusCode().value();
        };
        statuses.add(pool.submit(closeIt));
        statuses.add(pool.submit(closeIt));
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> status : statuses) {
            codes.add(status.get());
        }
        pool.shutdown();

        // Exactly one close happened; the other was told it was already done.
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        // 9,000 after opening, plus the 1,000 margin back once. Credited twice, this
        // would read 11,000 — money created by clicking.
        var user = userRepository.findByUsername("cfd-doubleclick").orElseThrow();
        assertThat(user.getCashBalance()).isEqualByComparingTo("10000");
    }

    @Test
    void closingTwiceInARowIsA409TheSecondTime() {
        stubQuote("1000.0");
        String cookie = signUp("cfd-twice");
        Long id = open(cookie, "LONG", "1", TradeAndAccountResponse.class).getBody().trade().id();

        close(cookie, id, TradeAndAccountResponse.class);
        var again = close(cookie, id, ApiError.class);

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().code()).isEqualTo("TRADE_ALREADY_CLOSED");
    }

    @Test
    void someoneElsesTradeIsNotFoundAndStaysOpen() {
        stubQuote("1000.0");
        String owner = signUp("cfd-owner");
        String intruder = signUp("cfd-intruder");
        Long id = open(owner, "LONG", "1", TradeAndAccountResponse.class).getBody().trade().id();

        var response = close(intruder, id, ApiError.class);

        // 404 and not 403 — a 403 would confirm the id is real.
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        var owned = userRepository.findByUsername("cfd-owner").orElseThrow();
        assertThat(tradeRepository.findByIdAndUserId(id, owned.getId()).orElseThrow().isOpen()).isTrue();
    }

    // ---- refusals ----------------------------------------------------------------------

    @Test
    void anUnaffordableTradeIsA409AndWritesNothing() {
        stubQuote("1000.0");
        String cookie = signUp("cfd-broke");

        var response = open(cookie, "SHORT", "11", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_FUNDS");
        var user = userRepository.findByUsername("cfd-broke").orElseThrow();
        assertThat(user.getCashBalance()).isEqualByComparingTo("10000");
        assertThat(tradeRepository.findByUserIdAndSymbolOrderByOpenedAtDescIdDesc(user.getId(), "BTC/USD"))
                .isEmpty();
    }

    @Test
    void anUnusablePriceIsA503AndNotAFill() {
        // Finnhub answers 200 with c:0 for a symbol it has no data for. A failure, not a
        // price — a zero fill would be a permanent wrong number.
        stubQuote("0");
        String cookie = signUp("cfd-noprice");

        var response = open(cookie, "LONG", "1", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("LIVE_PRICE_UNAVAILABLE");
    }

    @Test
    void aWrongSymbolIs404AndABadOrderIs400() {
        stubQuote("1000.0");
        String cookie = signUp("cfd-bad");

        var wrongSymbol = post(cookie, "/api/trades",
                "{\"symbol\":\"EUR/USD\",\"direction\":\"LONG\",\"quantity\":1}", ApiError.class);
        var oldSide = post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"side\":\"BUY\",\"quantity\":1}", ApiError.class);
        var zero = open(cookie, "LONG", "0", ApiError.class);

        assertThat(wrongSymbol.getStatusCode().value()).isEqualTo(404);
        // No direction, no trade.
        assertThat(oldSide.getStatusCode().value()).isEqualTo(400);
        assertThat(oldSide.getBody().code()).isEqualTo("INVALID_BODY");
        assertThat(zero.getStatusCode().value()).isEqualTo(400);
    }

    // ---- history, and the account on the chart ---------------------------------------

    @Test
    void historyIsThisUsersTradesNewestFirstWithResultsOnlyForClosedOnes() {
        stubQuote("1000.0");
        String mine = signUp("cfd-history");
        String theirs = signUp("cfd-history-other");

        Long first = open(mine, "LONG", "1", TradeAndAccountResponse.class).getBody().trade().id();
        stubQuote("1050.0");
        close(mine, first, String.class);
        open(mine, "SHORT", "1", String.class);
        open(theirs, "LONG", "2", String.class);

        var response = getAs(mine, "/api/trades?symbol=BTC/USD", TradeHistoryResponse.class);

        assertThat(response.getBody().trades()).hasSize(2);
        assertThat(response.getBody().trades().get(0).direction()).isEqualTo("SHORT");
        assertThat(response.getBody().trades().get(0).pnl()).isNull();   // open: no live price here
        assertThat(response.getBody().trades().get(1).pnl()).isEqualByComparingTo("50");
    }

    @Test
    void theChartAlwaysCarriesTheAccountAndValuesOpenTradesAtItsOwnPrice() {
        stubBackfill();
        stubQuote("1000.0");
        String cookie = signUp("cfd-chart");

        var before = getAs(cookie, "/api/getLiveChart?symbol=BTC/USD", LiveChartResponse.class);
        // Never null: a user who has never traded still has cash worth showing.
        assertThat(before.getBody().account()).isNotNull();
        assertThat(before.getBody().account().cash()).isEqualByComparingTo("10000");
        assertThat(before.getBody().account().openTrades()).isEmpty();

        open(cookie, "LONG", "1", String.class);

        var after = getAs(cookie, "/api/getLiveChart?symbol=BTC/USD", LiveChartResponse.class);
        assertThat(after.getBody().account().cash()).isEqualByComparingTo("9000");
        assertThat(after.getBody().account().margin()).isEqualByComparingTo("1000");
        assertThat(after.getBody().account().openTrades()).hasSize(1);
    }

    @Test
    void everyEndpointIs401WhenLoggedOut() {
        var opened = open(null, "LONG", "1", ApiError.class);
        var closed = close(null, 1L, ApiError.class);
        var history = getAs(null, "/api/trades?symbol=BTC/USD", ApiError.class);

        assertThat(opened.getStatusCode().value()).isEqualTo(401);
        assertThat(closed.getStatusCode().value()).isEqualTo(401);
        assertThat(history.getStatusCode().value()).isEqualTo(401);
        assertThat(history.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
    }

    // ---- helpers -------------------------------------------------------------------------

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

    private <T> ResponseEntity<T> open(String cookie, String direction, String quantity, Class<T> type) {
        return post(cookie, "/api/trades",
                "{\"symbol\":\"BTC/USD\",\"direction\":\"" + direction + "\",\"quantity\":" + quantity + "}",
                type);
    }

    private <T> ResponseEntity<T> close(String cookie, Long id, Class<T> type) {
        return post(cookie, "/api/trades/" + id + "/close", null, type);
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
}
