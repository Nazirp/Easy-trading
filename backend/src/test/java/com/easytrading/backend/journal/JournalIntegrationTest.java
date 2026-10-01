package com.easytrading.backend.journal;

import com.easytrading.backend.common.ApiError;
import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.instrument.InstrumentType;
import com.easytrading.backend.journal.dto.JournalEntryResponse;
import com.easytrading.backend.journal.dto.JournalResponse;
import com.easytrading.backend.trading.Trade;
import com.easytrading.backend.trading.TradeRepository;
import com.easytrading.backend.trading.TradeDirection;
import com.easytrading.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journal over real HTTP, against a real Postgres.
 *
 * {@code JournalServiceTest} already covers the rules without a container, so this
 * suite tests what a unit test cannot: that the JPA mapping matches the columns in
 * {@code db/schema.sql} (a mismatch is a refusal to start, not a subtle bug), that
 * {@code updated_at} really does arrive null and really does change on an edit, and
 * above all <b>that one user cannot reach another user's entries through the API</b>.
 *
 * <h3>No WireMock, and that is a deliberate assertion</h3>
 *
 * Every other integration suite in this project stubs Twelve Data or Finnhub. This
 * one points both base URLs at a port nothing listens on, because the journal must
 * not touch the price stack at all. If a journal request ever starts reaching for a price,
 * these tests fail with a connection error rather than quietly passing.
 *
 * The trades that entries link to are inserted through {@code TradeRepository} for
 * the same reason: going through {@code POST /api/trades} would drag the live price
 * into a suite that is not about it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JournalIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("easytrading")
            .withUsername("easytrading")
            .withPassword("easytrading")
            .withInitScript("schema.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Unreachable on purpose -- see the class javadoc.
        registry.add("marketdata.twelvedata.base-url", () -> "http://localhost:1");
        registry.add("liveprice.finnhub.base-url", () -> "http://localhost:1");
        registry.add("liveprice.finnhub.stream-enabled", () -> "false");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JournalRepository journalRepository;

    @Autowired
    TradeRepository tradeRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InstrumentRepository instrumentRepository;

    /**
     * {@code TestRestTemplate}'s default request factory is
     * {@code SimpleClientHttpRequestFactory}, which is built on
     * {@code HttpURLConnection} and rejects PATCH outright — the failure is a
     * {@code ProtocolException} about an invalid method, which looks like a bug in the
     * controller and is not. The JDK's own HTTP client supports it.
     */
    @BeforeEach
    void useARequestFactoryThatSupportsPatch() {
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

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

    private Long userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private void givenBitcoinExists() {
        if (!instrumentRepository.existsById("BTC/USD")) {
            instrumentRepository.save(
                    new Instrument("BTC/USD", "Bitcoin / US Dollar", null, InstrumentType.CRYPTO));
        }
    }

    private Trade givenATradeFor(String username) {
        givenBitcoinExists();
        return tradeRepository.save(new Trade(userId(username), "BTC/USD", TradeDirection.LONG,
                new BigDecimal("0.00100000"), new BigDecimal("76000.00000"),
                LocalDateTime.now()));
    }

    private <T> ResponseEntity<T> send(String cookie, HttpMethod method, String url,
                                       String json, Class<T> type) {
        return restTemplate.exchange(url, method, new HttpEntity<>(json, headers(cookie)), type);
    }

    private <T> ResponseEntity<T> getAs(String cookie, String url, Class<T> type) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(cookie)), type);
    }

    private Long createEntry(String cookie, String json) {
        var response = send(cookie, HttpMethod.POST, "/api/journal", json, JournalEntryResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().id();
    }

    // ---- the tests that matter most --------------------------------------

    @Test
    void anotherUsersEntryIsNotFoundOnEveryEndpointThatNamesAnId() {
        String mine = signUp("journal-owner");
        String theirs = signUp("journal-intruder");

        Long id = createEntry(mine, "{\"body\":\"my private thinking\"}");

        var patch = send(theirs, HttpMethod.PATCH, "/api/journal/" + id,
                "{\"body\":\"rewritten\"}", ApiError.class);
        var delete = send(theirs, HttpMethod.DELETE, "/api/journal/" + id, null, ApiError.class);

        // 404 and not 403. A 403 would confirm the id is real, which is exactly what
        // walking a small integer id space is looking for.
        assertThat(patch.getStatusCode().value()).isEqualTo(404);
        assertThat(patch.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(delete.getStatusCode().value()).isEqualTo(404);

        // ...and the entry is untouched, which is the part a status code does not say.
        var stored = journalRepository.findByIdAndUserId(id, userId("journal-owner")).orElseThrow();
        assertThat(stored.getBody()).isEqualTo("my private thinking");
        assertThat(stored.getUpdatedAt()).isNull();
    }

    @Test
    void anIdThatDoesNotExistIsAnsweredExactlyLikeOneThatBelongsToSomebodyElse() {
        String mine = signUp("journal-missing");
        Long id = createEntry(mine, "{\"body\":\"mine\"}");

        var real = send(signUp("journal-missing-other"), HttpMethod.PATCH, "/api/journal/" + id,
                "{\"body\":\"x\"}", ApiError.class);
        var imaginary = send(mine, HttpMethod.PATCH, "/api/journal/999999",
                "{\"body\":\"x\"}", ApiError.class);

        // Same status AND same code, so the response cannot be used to tell an
        // existing entry from a missing one.
        assertThat(real.getStatusCode().value()).isEqualTo(imaginary.getStatusCode().value());
        assertThat(real.getBody().code()).isEqualTo(imaginary.getBody().code());
    }

    @Test
    void theListIsThisUsersOnlyAndNewestFirst() {
        String mine = signUp("journal-list");
        String theirs = signUp("journal-list-other");

        createEntry(mine, "{\"body\":\"first\"}");
        createEntry(mine, "{\"body\":\"second\"}");
        createEntry(theirs, "{\"body\":\"not yours\"}");

        var response = getAs(mine, "/api/journal", JournalResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().entries()).hasSize(2);
        assertThat(response.getBody().entries().get(0).body()).isEqualTo("second");
        assertThat(response.getBody().entries().get(1).body()).isEqualTo("first");
    }

    // ---- links ------------------------------------------------------------

    @Test
    void aLinkedTradeRoundTripsAndBringsItsSymbolWithIt() {
        String cookie = signUp("journal-link");
        Trade trade = givenATradeFor("journal-link");

        // The request says nothing about the instrument; the trade decides it.
        var response = send(cookie, HttpMethod.POST, "/api/journal",
                "{\"body\":\"sized this one properly\",\"tradeId\":" + trade.getId() + "}",
                JournalEntryResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().tradeId()).isEqualTo(trade.getId());
        assertThat(response.getBody().symbol()).isEqualTo("BTC/USD");
        assertThat(response.getBody().createdAt()).isNotNull();
        assertThat(response.getBody().updatedAt()).isNull();
    }

    @Test
    void aTradeIdBelongingToSomebodyElseIsA404() {
        signUp("journal-trade-owner");
        Trade theirTrade = givenATradeFor("journal-trade-owner");
        String intruder = signUp("journal-trade-intruder");

        var response = send(intruder, HttpMethod.POST, "/api/journal",
                "{\"body\":\"nice trade\",\"tradeId\":" + theirTrade.getId() + "}", ApiError.class);

        // Without this, posting entries with tradeId 1, 2, 3... and watching which are
        // accepted would report how many trades other people have placed.
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void anUnknownSymbolIsA404AndAnEntryWithNoLinksIsFine() {
        String cookie = signUp("journal-symbol");

        var bad = send(cookie, HttpMethod.POST, "/api/journal",
                "{\"body\":\"note\",\"symbol\":\"NOPE/USD\"}", ApiError.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(404);

        var plain = send(cookie, HttpMethod.POST, "/api/journal",
                "{\"body\":\"no links at all\"}", JournalEntryResponse.class);
        assertThat(plain.getStatusCode().value()).isEqualTo(201);
        assertThat(plain.getBody().symbol()).isNull();
        assertThat(plain.getBody().tradeId()).isNull();
    }

    // ---- editing ----------------------------------------------------------

    @Test
    void anEditChangesTheTextAndStampsUpdatedAtAndLeavesTheLinksAlone() {
        String cookie = signUp("journal-edit");
        Trade trade = givenATradeFor("journal-edit");
        Long id = createEntry(cookie,
                "{\"body\":\"first thought\",\"tradeId\":" + trade.getId() + "}");

        var response = send(cookie, HttpMethod.PATCH, "/api/journal/" + id,
                "{\"body\":\"second thought\"}", JournalEntryResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().body()).isEqualTo("second thought");
        assertThat(response.getBody().updatedAt()).isNotNull();
        assertThat(response.getBody().tradeId()).isEqualTo(trade.getId());
        assertThat(response.getBody().symbol()).isEqualTo("BTC/USD");
    }

    @Test
    void anUnlinkedEntryCanBeLinkedOnEditOnceAndNeverRepointed() {
        String cookie = signUp("journal-link-later");
        Trade first = givenATradeFor("journal-link-later");
        Trade second = givenATradeFor("journal-link-later");
        Long id = createEntry(cookie, "{\"body\":\"written before the trade\"}");

        var linked = send(cookie, HttpMethod.PATCH, "/api/journal/" + id,
                "{\"body\":\"written before the trade\",\"tradeId\":" + first.getId() + "}",
                JournalEntryResponse.class);
        var repointed = send(cookie, HttpMethod.PATCH, "/api/journal/" + id,
                "{\"body\":\"now about the other one\",\"tradeId\":" + second.getId() + "}",
                ApiError.class);

        assertThat(linked.getStatusCode().value()).isEqualTo(200);
        assertThat(linked.getBody().tradeId()).isEqualTo(first.getId());
        assertThat(linked.getBody().symbol()).isEqualTo("BTC/USD");
        assertThat(repointed.getStatusCode().value()).isEqualTo(409);
        assertThat(repointed.getBody().code()).isEqualTo("ALREADY_LINKED");
        assertThat(journalRepository.findById(id).orElseThrow().getTradeId()).isEqualTo(first.getId());
    }

    @Test
    void linkingSomebodyElsesTradeOnEditIsNotFound() {
        String cookie = signUp("journal-link-theirs");
        signUp("journal-link-other");
        Trade theirs = givenATradeFor("journal-link-other");
        Long id = createEntry(cookie, "{\"body\":\"mine\"}");

        var response = send(cookie, HttpMethod.PATCH, "/api/journal/" + id,
                "{\"body\":\"mine\",\"tradeId\":" + theirs.getId() + "}", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(journalRepository.findById(id).orElseThrow().getTradeId()).isNull();
    }

    @Test
    void anEmptyBodyIsA400OnCreateAndOnEdit() {
        String cookie = signUp("journal-blank");
        Long id = createEntry(cookie, "{\"body\":\"something\"}");

        var created = send(cookie, HttpMethod.POST, "/api/journal",
                "{\"body\":\"   \"}", ApiError.class);
        var edited = send(cookie, HttpMethod.PATCH, "/api/journal/" + id,
                "{\"body\":\"\"}", ApiError.class);

        assertThat(created.getStatusCode().value()).isEqualTo(400);
        assertThat(created.getBody().code()).isEqualTo("INVALID_BODY");
        assertThat(edited.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deletingYourOwnEntryRemovesTheRow() {
        String cookie = signUp("journal-delete");
        Long id = createEntry(cookie, "{\"body\":\"regret writing this\"}");

        var response = send(cookie, HttpMethod.DELETE, "/api/journal/" + id, null, Void.class);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(journalRepository.findByIdAndUserId(id, userId("journal-delete"))).isEmpty();
    }

    // ---- the mapping, and being logged out ---------------------------------

    @Test
    void theEntityMappingMatchesTheColumnsAndUpdatedAtStartsNull() {
        String cookie = signUp("journal-mapping");
        Long id = createEntry(cookie, "{\"body\":\"read me back\"}");

        var stored = journalRepository.findByIdAndUserId(id, userId("journal-mapping")).orElseThrow();

        assertThat(stored.getBody()).isEqualTo("read me back");
        assertThat(stored.getCreatedAt()).isNotNull();
        // Not equal to created_at, not "now" -- absent. Telling "never edited" from
        // "edited instantly" is the only thing this column is for.
        assertThat(stored.getUpdatedAt()).isNull();
    }

    @Test
    void allFourEndpointsAre401WhenLoggedOut() {
        String cookie = signUp("journal-401");
        Long id = createEntry(cookie, "{\"body\":\"mine\"}");

        var list = getAs(null, "/api/journal", ApiError.class);
        var create = send(null, HttpMethod.POST, "/api/journal", "{\"body\":\"x\"}", ApiError.class);
        var patch = send(null, HttpMethod.PATCH, "/api/journal/" + id, "{\"body\":\"x\"}", ApiError.class);
        var delete = send(null, HttpMethod.DELETE, "/api/journal/" + id, null, ApiError.class);

        assertThat(list.getStatusCode().value()).isEqualTo(401);
        assertThat(create.getStatusCode().value()).isEqualTo(401);
        assertThat(patch.getStatusCode().value()).isEqualTo(401);
        assertThat(delete.getStatusCode().value()).isEqualTo(401);
        assertThat(list.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
    }
}
