package com.easytrading.backend.user;

import java.math.BigDecimal;
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
import com.easytrading.backend.user.dto.UserResponse;

/**
 * Signup, login, logout and session handling, exercised
 * over real HTTP against a real (Testcontainers) Postgres built from
 * db/schema.sql, so the app_user table under test is the one the app will ship
 * against.
 *
 * TestRestTemplate does not keep cookies between calls, which is useful here:
 * every request states explicitly whether it carries a session or not, so a
 * test can never pass by accident on a session left over from the previous one.
 * The session cookie is captured from the login response and replayed by hand.
 *
 * Run locally with
 * Docker running: `mvn test`.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("easytrading")
            .withUsername("easytrading")
            .withPassword("easytrading")
            // Copied from ../db/schema.sql onto the test classpath by the
            // maven-resources-plugin execution in pom.xml.
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
    UserRepository userRepository;

    // ---- helpers --------------------------------------------------------

    private static HttpEntity<String> json(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", headers);
    }

    private static HttpEntity<Void> withSession(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return new HttpEntity<>(headers);
    }

    /** The JSESSIONID a login/signup response handed back, cookie attributes stripped. */
    private static String sessionCookie(ResponseEntity<?> response) {
        List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("response should set a session cookie").isNotNull().isNotEmpty();
        return setCookie.get(0).split(";", 2)[0];
    }

    private ResponseEntity<UserResponse> signUp(String username, String password) {
        return restTemplate.postForEntity("/api/signup", json(username, password), UserResponse.class);
    }

    // ---- registration ---------------------------------------------------

    @Test
    void signupCreatesAnAccountWithTheDefaultVirtualBalance() {
        ResponseEntity<UserResponse> response = signUp("alice", "correct-horse");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().username()).isEqualTo("alice");
        // $10,000 to start. Compared by value, not by equals():
        // 10000.00 and 10000.00000 are the same amount but different
        // BigDecimal scales, and NUMERIC(18,5) gives back the latter.
        assertThat(response.getBody().cashBalance()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    void signupStoresAHashAndNeverThePlaintextPassword() {
        signUp("bob", "correct-horse");

        User stored = userRepository.findByUsername("bob").orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo("correct-horse");
        // BCrypt's own format: version, cost, then salt+hash.
        assertThat(stored.getPasswordHash()).startsWith("$2a$");
        assertThat(stored.getPasswordHash()).hasSize(60);
    }

    @Test
    void aTakenUsernameIsRejectedWithConflict() {
        signUp("carol", "correct-horse");

        ResponseEntity<ApiError> second =
                restTemplate.postForEntity("/api/signup", json("carol", "different-password"), ApiError.class);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody().code()).isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void tooShortAPasswordIsRejectedBeforeAnyRowIsWritten() {
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/signup", json("dave", "short"), ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REGISTRATION");
        assertThat(userRepository.findByUsername("dave")).isEmpty();
    }

    // ---- login ----------------------------------------------------------

    @Test
    void signupThenLoginSucceedsAndTheSessionIdentifiesTheUser() {
        signUp("erin", "correct-horse");

        ResponseEntity<UserResponse> login =
                restTemplate.postForEntity("/api/login", json("erin", "correct-horse"), UserResponse.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<UserResponse> me = restTemplate.exchange(
                "/api/me", HttpMethod.GET, withSession(sessionCookie(login)), UserResponse.class);

        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().username()).isEqualTo("erin");
    }

    @Test
    void aWrongPasswordIsRejected() {
        signUp("frank", "correct-horse");

        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/login", json("frank", "wrong-password"), ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void anUnknownUsernameFailsIdenticallyToAWrongPassword() {
        // The point of the test: a caller must not be able to tell which
        // usernames exist by comparing the two failures.
        signUp("grace", "correct-horse");

        ResponseEntity<ApiError> wrongPassword =
                restTemplate.postForEntity("/api/login", json("grace", "wrong-password"), ApiError.class);
        ResponseEntity<ApiError> noSuchUser =
                restTemplate.postForEntity("/api/login", json("nobody-here", "wrong-password"), ApiError.class);

        assertThat(noSuchUser.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
        assertThat(noSuchUser.getBody().code()).isEqualTo(wrongPassword.getBody().code());
        assertThat(noSuchUser.getBody().message()).isEqualTo(wrongPassword.getBody().message());
    }

    // ---- session lifetime -----------------------------------------------

    @Test
    void meIsUnauthorizedWithoutASession() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity("/api/me", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
    }

    @Test
    void logoutEndsTheSession() {
        signUp("heidi", "correct-horse");
        ResponseEntity<UserResponse> login =
                restTemplate.postForEntity("/api/login", json("heidi", "correct-horse"), UserResponse.class);
        String cookie = sessionCookie(login);

        ResponseEntity<Void> logout =
                restTemplate.exchange("/api/logout", HttpMethod.POST, withSession(cookie), Void.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<ApiError> me =
                restTemplate.exchange("/api/me", HttpMethod.GET, withSession(cookie), ApiError.class);
        assertThat(me.getStatusCode().value()).isEqualTo(401);
        assertThat(me.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
    }

    @Test
    void loggingInIssuesANewSessionId() {
        // Session fixation: the id the browser held before logging in must not
        // be the id that is now authenticated.
        signUp("ivan", "correct-horse");
        ResponseEntity<UserResponse> first =
                restTemplate.postForEntity("/api/login", json("ivan", "correct-horse"), UserResponse.class);
        String before = sessionCookie(first);

        ResponseEntity<UserResponse> second = restTemplate.exchange(
                "/api/login", HttpMethod.POST,
                new HttpEntity<>(json("ivan", "correct-horse").getBody(), headersWith(before)),
                UserResponse.class);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(sessionCookie(second)).isNotEqualTo(before);
    }

    private static HttpHeaders headersWith(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    // ---- the public endpoints stay public --------------------------------

    @Test
    void searchStillWorksWithNoAccount() {
        // Auth gates only user-scoped features. A 404
        // here means the request reached the search logic; a 401 would mean an
        // auth wall had been put in front of it.
        ResponseEntity<ApiError> response = restTemplate.getForEntity("/api/search?q=doesnotexist", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void getPriceStillWorksWithNoAccount() {
        // An unknown symbol is a 404 from the price logic,
        // not a 401 -- and no Twelve Data call is involved either way.
        ResponseEntity<ApiError> response =
                restTemplate.getForEntity("/api/getPrice?symbol=NOPE&interval=1day", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    // ---- malformed input -------------------------------------------------

    @Test
    void aMissingBodyIsRejectedInTheApisOwnErrorShape() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/login", HttpMethod.POST, new HttpEntity<>("", headers), ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_BODY");
    }
}
