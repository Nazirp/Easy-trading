package com.easytrading.backend.price;

import com.easytrading.backend.common.ApiError;
import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.instrument.InstrumentType;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /getPrice?symbol=&interval= — UC01 step 10. Covers: prices already cached
 * in Postgres (no external call), unknown symbol (404 NOT_FOUND, same signal as
 * search), unknown interval (400 INVALID_INTERVAL), and a known instrument with
 * no candles at that interval, which ingests on demand from a WireMock-stubbed
 * Twelve Data and persists what comes back.
 *
 * NOTE: not executed in the sandbox this was authored in — run locally with
 * Docker running: `mvn test`.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PriceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("easytrading")
            .withUsername("easytrading")
            .withPassword("easytrading")
            .withInitScript("schema.sql");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("marketdata.twelvedata.base-url", wireMock::baseUrl);
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    InstrumentRepository instrumentRepository;

    @Autowired
    PriceRepository priceRepository;

    @Test
    void returnsCachedPricesWithoutCallingMarketData() {
        instrumentRepository.save(new Instrument("EUR/USD", "Euro / US Dollar", null, InstrumentType.FOREX));
        priceRepository.save(new Price("EUR/USD", "1day", LocalDateTime.parse("2026-08-22T00:00:00"),
                new BigDecimal("1.07900"), new BigDecimal("1.08200"), new BigDecimal("1.07800"),
                new BigDecimal("1.08100"), null));

        var response = restTemplate.getForObject("/api/getPrice?symbol=EUR/USD&interval=1day", PricesFixture.class);

        assertThat(response.prices).hasSize(1);
        assertThat(response.prices.get(0).close).isEqualByComparingTo("1.08100");
        assertThat(response.interval).isEqualTo("1day");
        // signal always present, neutral until SCRUM-20 lands
        assertThat(response.signal.verdict).isEqualTo("NONE");
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/time_series")));
    }

    @Test
    void unknownSymbolReturnsNotFoundSignal() {
        var response = restTemplate.getForEntity("/api/getPrice?symbol=DOES/NOTEXIST&interval=1day", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void unknownIntervalIsRejected() {
        var response = restTemplate.getForEntity("/api/getPrice?symbol=EUR/USD&interval=banana", ApiError.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_INTERVAL");
    }

    @Test
    void ingestsFromMarketDataWhenInstrumentHasNoCandlesAtThatInterval() {
        instrumentRepository.save(new Instrument("BTC/USD", "Bitcoin / US Dollar", null, InstrumentType.CRYPTO));
        wireMock.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("BTC/USD"))
                .withQueryParam("interval", equalTo("4h"))
                .willReturn(okJson("""
                        {
                          "values": [
                            {"datetime": "2026-08-22 12:00:00", "open": "60000.0", "high": "60800.0", "low": "59500.0", "close": "60250.0", "volume": "1200"}
                          ],
                          "status": "ok"
                        }
                        """)));

        var response = restTemplate.getForObject("/api/getPrice?symbol=BTC/USD&interval=4h", PricesFixture.class);

        assertThat(response.prices).hasSize(1);
        assertThat(response.prices.get(0).close).isEqualByComparingTo("60250.0");
        // intraday datetime keeps its time of day, not flattened to midnight
        assertThat(response.prices.get(0).datetime).startsWith("2026-08-22T12:00");

        // and it's actually in Postgres now, under the right interval
        assertThat(priceRepository.findBySymbolAndIntervalOrderByDatetime("BTC/USD", "4h")).hasSize(1);
        assertThat(priceRepository.findBySymbolAndIntervalOrderByDatetime("BTC/USD", "1day")).isEmpty();
    }

    @Test
    void reingestsWhenCachedDataIsStale() {
        instrumentRepository.save(new Instrument("AAPL", "Apple Inc.", "NASDAQ", InstrumentType.STOCK));
        // One old candle -- stale (well past the 1day threshold) AND below
        // MIN_CANDLES on its own, either reason alone should trigger re-ingestion.
        priceRepository.save(new Price("AAPL", "1day", LocalDateTime.now().minusDays(10),
                new BigDecimal("150.00"), new BigDecimal("151.00"), new BigDecimal("149.00"),
                new BigDecimal("150.50"), null));

        wireMock.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withQueryParam("interval", equalTo("1day"))
                .willReturn(okJson("""
                        {
                          "values": [
                            {"datetime": "2026-08-29", "open": "228.0", "high": "230.0", "low": "227.0", "close": "229.5", "volume": "1000000"}
                          ],
                          "status": "ok"
                        }
                        """)));

        var response = restTemplate.getForObject("/api/getPrice?symbol=AAPL&interval=1day", PricesFixture.class);

        // Old cached candle is still there, new one is merged in -- both show up.
        assertThat(response.prices).hasSize(2);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/time_series")));
    }

    /** Minimal shape for TestRestTemplate deserialization — mirrors PricesResponse. */
    static class PricesFixture {
        public String symbol;
        public String interval;
        public java.util.List<PriceFixture> prices;
        public SignalFixture signal;
    }

    static class PriceFixture {
        public String datetime;
        public BigDecimal open;
        public BigDecimal high;
        public BigDecimal low;
        public BigDecimal close;
        public Long volume;
    }

    static class SignalFixture {
        public String verdict;
        public String label;
        public String explanation;
    }
}
