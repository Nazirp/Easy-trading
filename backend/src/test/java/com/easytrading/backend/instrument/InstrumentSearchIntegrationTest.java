package com.easytrading.backend.instrument;

import com.easytrading.backend.common.ApiError;
import com.easytrading.backend.instrument.dto.SearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /search — DB-only (scope decision, 2026-08-23): a real HTTP request
 * through business logic into a real (Testcontainers) Postgres and back. No
 * external market-data call is involved.
 *
 * NOTE: not executed in the sandbox this was authored in — that environment
 * blocks Maven Central, so `mvn test` couldn't run there. Run it locally with
 * Docker running: `mvn test`.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstrumentSearchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("easytrading")
            .withUsername("easytrading")
            .withPassword("easytrading")
            // schema.sql is copied from ../db/schema.sql onto the test classpath
            // by the maven-resources-plugin execution in pom.xml -- single
            // source of truth stays db/schema.sql, this is a build-time copy.
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
    InstrumentRepository repository;

    @Test
    void findsAnInstrumentAlreadyInTheDatabase() {
        repository.save(new Instrument("EUR/USD", "Euro / US Dollar", null, InstrumentType.FOREX, "OANDA:EUR_USD"));

        SearchResponse response = restTemplate.getForObject("/api/search?q=EUR", SearchResponse.class);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).symbol()).isEqualTo("EUR/USD");
        assertThat(response.results().get(0).type()).isEqualTo("forex");
    }

    @Test
    void returnsNotFoundSignalWhenNothingMatchesInTheDb() {
        var response = restTemplate.getForEntity("/api/search?q=doesnotexist", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void emptyQueryIsRejectedWithoutTouchingTheDatabase() {
        var response = restTemplate.getForEntity("/api/search?q=", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_QUERY");
    }
}
