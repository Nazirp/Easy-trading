package com.easytrading.backend.price;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.easytrading.backend.common.ApiError;
import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.instrument.InstrumentType;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * GET /getPrice?symbol=&interval= — UC01 step 10. Covers: prices already cached
 * in Postgres (no external call), unknown symbol (404 NOT_FOUND, same signal as
 * search), unknown interval (400 INVALID_INTERVAL), and a known instrument with
 * no candles at that interval, which ingests on demand from a WireMock-stubbed
 * Twelve Data and persists what comes back.
 *
 * SCRUM-62 adds the remaining two intervals (2h, 1week), the outputsize that
 * must go out with every ingest call, and the display-window cap.
 *
 * `2h` fails on any database whose volume predates the CHECK constraint change
 * in db/schema.sql -- Testcontainers builds a fresh one from schema.sql here, so
 * this suite is unaffected, but a local run against a stale volume is not:
 * `docker compose down -v && docker compose up --build`.
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
        // TWO candles, both recent: one candle is below MIN_CANDLES and a fixed
        // past date is stale on any threshold, either of which makes
        // needsIngestion() true and sends the service to Twelve Data -- so the
        // "no external call" assertion below could never have held.
        priceRepository.save(new Price("EUR/USD", "1day", LocalDateTime.now().minusDays(1).withNano(0),
                new BigDecimal("1.07900"), new BigDecimal("1.08200"), new BigDecimal("1.07800"),
                new BigDecimal("1.08000"), null));
        priceRepository.save(new Price("EUR/USD", "1day", LocalDateTime.now().withNano(0),
                new BigDecimal("1.08000"), new BigDecimal("1.08300"), new BigDecimal("1.07850"),
                new BigDecimal("1.08100"), null));

        wireMock.resetRequests();
        var response = restTemplate.getForObject("/api/getPrice?symbol=EUR/USD&interval=1day", PricesFixture.class);

        assertThat(response.prices).hasSize(2);
        // oldest-first: the newest candle is last
        assertThat(response.prices.get(1).close).isEqualByComparingTo("1.08100");
        assertThat(response.interval).isEqualTo("1day");
        // signal always present, neutral until SCRUM-46 lands
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

        // and it's actually in Postgres now, under the right interval.
        // Storing under the wrong interval is the failure the CHECK constraint in
        // db/schema.sql exists to prevent: it reads back as "not ingested yet".
        assertThat(priceRepository.findBySymbolAndIntervalOrderByDatetime("BTC/USD", "4h")).hasSize(1);
        assertThat(priceRepository.findBySymbolAndIntervalOrderByDatetime("BTC/USD", "1day")).isEmpty();

        // outputsize must actually go out -- omitting it makes Twelve Data
        // default to 30 candles, short of every range (SCRUM-62). 4h displays
        // 180, plus a 20-candle signal warm-up.
        wireMock.verify(getRequestedFor(urlPathEqualTo("/time_series"))
                .withQueryParam("outputsize", equalTo("200")));
    }

    @Test
    void ingestsTwoHourCandlesForTheOneWeekRange() {
        instrumentRepository.save(new Instrument("ETH/USD", "Ethereum / US Dollar", null, InstrumentType.CRYPTO));
        wireMock.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("ETH/USD"))
                .withQueryParam("interval", equalTo("2h"))
                .willReturn(okJson("""
                        {
                          "values": [
                            {"datetime": "2026-08-22 14:00:00", "open": "2600.0", "high": "2640.0", "low": "2580.0", "close": "2630.0", "volume": "800"}
                          ],
                          "status": "ok"
                        }
                        """)));

        var response = restTemplate.getForObject("/api/getPrice?symbol=ETH/USD&interval=2h", PricesFixture.class);

        assertThat(response.interval).isEqualTo("2h");
        assertThat(response.prices).hasSize(1);
        assertThat(response.prices.get(0).datetime).startsWith("2026-08-22T14:00");
        assertThat(priceRepository.findBySymbolAndIntervalOrderByDatetime("ETH/USD", "2h")).hasSize(1);
        // 2h displays 84 candles + 20 warm-up
        wireMock.verify(getRequestedFor(urlPathEqualTo("/time_series"))
                .withQueryParam("outputsize", equalTo("104")));
    }

    @Test
    void ingestsWeeklyCandlesForTheOneYearRange() {
        instrumentRepository.save(new Instrument("MSFT", "Microsoft Corporation", "NASDAQ", InstrumentType.STOCK));
        wireMock.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("MSFT"))
                .withQueryParam("interval", equalTo("1week"))
                .willReturn(okJson("""
                        {
                          "values": [
                            {"datetime": "2026-08-17", "open": "410.0", "high": "418.0", "low": "405.0", "close": "415.0", "volume": "5000000"},
                            {"datetime": "2026-08-24", "open": "415.0", "high": "422.0", "low": "412.0", "close": "420.0", "volume": "4800000"}
                          ],
                          "status": "ok"
                        }
                        """)));

        var response = restTemplate.getForObject("/api/getPrice?symbol=MSFT&interval=1week", PricesFixture.class);

        assertThat(response.prices).hasSize(2);
        // weekly candles land on midnight, and come back oldest-first for the chart
        assertThat(response.prices.get(0).datetime).startsWith("2026-08-17T00:00");
        assertThat(response.prices.get(1).datetime).startsWith("2026-08-24T00:00");
        // 1week displays 52 candles + 20 warm-up
        wireMock.verify(getRequestedFor(urlPathEqualTo("/time_series"))
                .withQueryParam("outputsize", equalTo("72")));
    }

    @Test
    void servesTwoHourCandlesFromCacheWithoutCallingMarketData() {
        instrumentRepository.save(new Instrument("GBP/USD", "British Pound / US Dollar", null, InstrumentType.FOREX));
        // two recent candles: enough to clear MIN_CANDLES and inside the 2h
        // staleness threshold (1.5x the candle length = 3h), so no ingest
        priceRepository.save(new Price("GBP/USD", "2h", LocalDateTime.now().minusHours(4),
                new BigDecimal("1.26000"), new BigDecimal("1.26500"), new BigDecimal("1.25800"),
                new BigDecimal("1.26200"), null));
        priceRepository.save(new Price("GBP/USD", "2h", LocalDateTime.now().minusHours(2),
                new BigDecimal("1.26200"), new BigDecimal("1.26900"), new BigDecimal("1.26100"),
                new BigDecimal("1.26700"), null));

        wireMock.resetRequests();
        var response = restTemplate.getForObject("/api/getPrice?symbol=GBP/USD&interval=2h", PricesFixture.class);

        assertThat(response.prices).hasSize(2);
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/time_series")));
    }

    @Test
    void cachedSeriesIsCappedAtTheDisplayWindowAndOldestFirst() {
        instrumentRepository.save(new Instrument("NFLX", "Netflix Inc.", "NASDAQ", InstrumentType.STOCK));
        // 60 weekly candles cached, but the 1yr range only displays 52
        for (int weeksAgo = 60; weeksAgo >= 1; weeksAgo--) {
            priceRepository.save(new Price("NFLX", "1week", LocalDateTime.now().minusWeeks(weeksAgo).withNano(0),
                    new BigDecimal("500.00"), new BigDecimal("505.00"), new BigDecimal("495.00"),
                    new BigDecimal(String.valueOf(1000 - weeksAgo)), null));
        }

        wireMock.resetRequests();
        var response = restTemplate.getForObject("/api/getPrice?symbol=NFLX&interval=1week", PricesFixture.class);

        // capped at the window, not everything we hold
        assertThat(response.prices).hasSize(52);
        // and it's the NEWEST 52, oldest-first: close encodes 1000 - weeksAgo,
        // so the last element is the most recent candle (weeksAgo = 1)
        assertThat(response.prices.get(0).close).isEqualByComparingTo("948");
        assertThat(response.prices.get(51).close).isEqualByComparingTo("999");
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/time_series")));
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
