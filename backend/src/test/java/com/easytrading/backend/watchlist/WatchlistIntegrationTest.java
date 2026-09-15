package com.easytrading.backend.watchlist;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
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
import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.instrument.InstrumentType;
import com.easytrading.backend.instrument.dto.InstrumentMatchResponse;
import com.easytrading.backend.watchlist.dto.WatchlistResponse;

/**
 * SCRUM-22 / SCRUM-70 — the account-scoped watchlist over real HTTP against a
 * real (Testcontainers) Postgres built from db/schema.sql.
 *
 * Accounts are created through /api/signup rather than by inserting rows, so
 * these tests exercise the same path a browser takes and would catch auth and
 * watchlist drifting apart. Session cookies are captured and replayed by hand,
 * because TestRestTemplate keeps none — which is what lets one test act as two
 * different users, and lets another act as nobody at all.
 *
 * NOTE: not executed in the sandbox this was authored in — that environment
 * blocks Maven Central, so `mvn test` couldn't run there. Run locally with
 * Docker running: `mvn test`.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchlistIntegrationTest {

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
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    InstrumentRepository instrumentRepository;

    // ---- helpers --------------------------------------------------------

    private void givenInstruments() {
        instrumentRepository.save(new Instrument("EUR/USD", "Euro / US Dollar", null, InstrumentType.FOREX));
        instrumentRepository.save(new Instrument("BTC/USD", "Bitcoin / US Dollar", null, InstrumentType.CRYPTO));
        instrumentRepository.save(new Instrument("AAPL", "Apple Inc.", "NASDAQ", InstrumentType.STOCK));
    }

    private static HttpHeaders jsonHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    private static String sessionCookie(ResponseEntity<?> response) {
        List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("expected a session cookie").isNotNull().isNotEmpty();
        return setCookie.get(0).split(";", 2)[0];
    }

    /** Registers a user and returns their session cookie. */
    private String signUp(String username) {
        HttpEntity<String> body = new HttpEntity<>(
                "{\"username\":\"" + username + "\",\"password\":\"correct-horse\"}", jsonHeaders(null));
        ResponseEntity<String> response = restTemplate.postForEntity("/api/signup", body, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return sessionCookie(response);
    }

    private <T> ResponseEntity<T> add(String cookie, String symbol, Class<T> type) {
        return restTemplate.exchange("/api/watchlist", HttpMethod.POST,
                new HttpEntity<>("{\"symbol\":\"" + symbol + "\"}", jsonHeaders(cookie)), type);
    }

    private ResponseEntity<WatchlistResponse> list(String cookie) {
        return restTemplate.exchange("/api/watchlist", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(cookie)), WatchlistResponse.class);
    }

    private ResponseEntity<Void> remove(String cookie, String symbol) {
        return restTemplate.exchange("/api/watchlist?symbol={s}", HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(cookie)), Void.class, symbol);
    }

    // ---- the happy path --------------------------------------------------

    @Test
    void aNewAccountHasAnEmptyWatchlist() {
        String cookie = signUp("wl-empty");

        ResponseEntity<WatchlistResponse> response = list(cookie);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Empty, and a 200 -- "you have saved nothing" must not look like an error.
        assertThat(response.getBody().items()).isEmpty();
    }

    @Test
    void addingAnInstrumentPutsItOnTheList() {
        givenInstruments();
        String cookie = signUp("wl-add");

        ResponseEntity<InstrumentMatchResponse> added = add(cookie, "EUR/USD", InstrumentMatchResponse.class);
        assertThat(added.getStatusCode().value()).isEqualTo(201);
        assertThat(added.getBody().symbol()).isEqualTo("EUR/USD");
        assertThat(added.getBody().name()).isEqualTo("Euro / US Dollar");
        assertThat(added.getBody().type()).isEqualTo("forex");

        List<InstrumentMatchResponse> items = list(cookie).getBody().items();
        assertThat(items).hasSize(1);
        // Symbol and name per instrument, and nothing about a signal -- the
        // signal belongs to the chart view only (UC03 AC).
        assertThat(items.get(0).symbol()).isEqualTo("EUR/USD");
        assertThat(items.get(0).name()).isEqualTo("Euro / US Dollar");
    }

    @Test
    void theListIsOldestSavedFirst() {
        givenInstruments();
        String cookie = signUp("wl-order");

        add(cookie, "AAPL", String.class);
        add(cookie, "BTC/USD", String.class);
        add(cookie, "EUR/USD", String.class);

        // Insertion order, not alphabetical: a list that reorders itself when
        // you add to it is disorienting.
        assertThat(list(cookie).getBody().items())
                .extracting(InstrumentMatchResponse::symbol)
                .containsExactly("AAPL", "BTC/USD", "EUR/USD");
    }

    @Test
    void removingTakesItOffAndRemovingAgainIsStillFine() {
        givenInstruments();
        String cookie = signUp("wl-remove");
        add(cookie, "BTC/USD", String.class);

        assertThat(remove(cookie, "BTC/USD").getStatusCode().value()).isEqualTo(204);
        assertThat(list(cookie).getBody().items()).isEmpty();

        // Idempotent: the caller wanted it gone, and it is gone.
        assertThat(remove(cookie, "BTC/USD").getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void aSymbolContainingASlashSurvivesTheDeleteRoundTrip() {
        // The reason DELETE takes a query parameter instead of a path segment.
        // "BTC/USD" in a path would not route at all.
        givenInstruments();
        String cookie = signUp("wl-slash");
        add(cookie, "BTC/USD", String.class);

        assertThat(remove(cookie, "BTC/USD").getStatusCode().value()).isEqualTo(204);
        assertThat(list(cookie).getBody().items()).isEmpty();
    }

    // ---- the rules -------------------------------------------------------

    @Test
    void theSameInstrumentCannotBeAddedTwice() {
        givenInstruments();
        String cookie = signUp("wl-dupe");
        add(cookie, "AAPL", String.class);

        ResponseEntity<ApiError> second = add(cookie, "AAPL", ApiError.class);

        assertThat(second.getStatusCode().value()).isEqualTo(409);       // UC03 BR1
        assertThat(second.getBody().code()).isEqualTo("ALREADY_ON_WATCHLIST");
        assertThat(list(cookie).getBody().items()).hasSize(1);
    }

    @Test
    void anUnknownSymbolIsNotFound() {
        givenInstruments();
        String cookie = signUp("wl-unknown");

        ResponseEntity<ApiError> response = add(cookie, "NOPE", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void twoUsersCanSaveTheSameInstrumentAndNeitherSeesTheOthersList() {
        givenInstruments();
        String alice = signUp("wl-alice");
        String bob = signUp("wl-bob");

        add(alice, "EUR/USD", String.class);
        add(alice, "AAPL", String.class);
        add(bob, "EUR/USD", String.class);   // the same instrument, a different user

        assertThat(list(alice).getBody().items())
                .extracting(InstrumentMatchResponse::symbol)
                .containsExactly("EUR/USD", "AAPL");
        assertThat(list(bob).getBody().items())
                .extracting(InstrumentMatchResponse::symbol)
                .containsExactly("EUR/USD");

        // And one user removing it does not touch the other's row.
        remove(alice, "EUR/USD");
        assertThat(list(bob).getBody().items()).hasSize(1);
    }

    // ---- logged out ------------------------------------------------------

    @Test
    void everyEndpointIsUnauthorizedWithoutASession() {
        givenInstruments();

        ResponseEntity<ApiError> listed = restTemplate.exchange("/api/watchlist", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)), ApiError.class);
        ResponseEntity<ApiError> added = add(null, "EUR/USD", ApiError.class);
        ResponseEntity<ApiError> removed = restTemplate.exchange("/api/watchlist?symbol={s}", HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(null)), ApiError.class, "EUR/USD");

        // 401 on all three -- never a 500, and never an empty list, which the
        // frontend would render as "you have saved nothing".
        assertThat(listed.getStatusCode().value()).isEqualTo(401);
        assertThat(listed.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
        assertThat(added.getStatusCode().value()).isEqualTo(401);
        assertThat(added.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
        assertThat(removed.getStatusCode().value()).isEqualTo(401);
        assertThat(removed.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
    }

    @Test
    void loggingOutEndsAccessToTheList() {
        givenInstruments();
        String cookie = signUp("wl-logout");
        add(cookie, "AAPL", String.class);

        restTemplate.exchange("/api/logout", HttpMethod.POST, new HttpEntity<>(jsonHeaders(cookie)), Void.class);

        ResponseEntity<ApiError> after = restTemplate.exchange("/api/watchlist", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(cookie)), ApiError.class);
        assertThat(after.getStatusCode().value()).isEqualTo(401);
    }

    // ---- the public endpoints stay public ---------------------------------

    @Test
    void searchStillWorksWithNoAccount() {
        givenInstruments();

        ResponseEntity<String> response = restTemplate.getForEntity("/api/search?q=EUR", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
