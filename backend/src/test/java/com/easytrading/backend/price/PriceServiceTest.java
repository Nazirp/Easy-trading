package com.easytrading.backend.price;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for PriceService.needsIngestion() (SCRUM follow-up on
 * getPrices()'s cache staleness check) -- no Spring context, no database.
 * The MISSING/INSUFFICIENT/OK classification only reads its parameters, so
 * the repositories/client the constructor asks for are never touched and
 * can be left null.
 */
class PriceServiceTest {

    // needsIngestion() only reads its parameters, so every collaborator can be null.
    private final PriceService service = new PriceService(null, null, null, null);

    private Price priceAt(LocalDateTime datetime) {
        return new Price("EUR/USD", "1day", datetime,
                new BigDecimal("1.08"), new BigDecimal("1.09"), new BigDecimal("1.07"), new BigDecimal("1.08"), null);
    }

    @Test
    void emptyCacheIsMissing() {
        assertThat(service.needsIngestion(List.of(), Interval.ONE_DAY)).isTrue();
    }

    @Test
    void tooFewCandlesIsInsufficientEvenIfRecent() {
        List<Price> cached = List.of(priceAt(LocalDateTime.now()));

        assertThat(service.needsIngestion(cached, Interval.ONE_DAY)).isTrue();
    }

    @Test
    void staleNewestCandleIsInsufficient() {
        // ascending by datetime, like the real repository query returns
        List<Price> cached = List.of(
                priceAt(LocalDateTime.now().minusDays(5)),
                priceAt(LocalDateTime.now().minusDays(3))); // newest is still older than the 1day threshold

        assertThat(service.needsIngestion(cached, Interval.ONE_DAY)).isTrue();
    }

    @Test
    void enoughRecentCandlesIsOk() {
        List<Price> cached = List.of(
                priceAt(LocalDateTime.now().minusHours(20)),
                priceAt(LocalDateTime.now().minusHours(4))); // newest is within the 1day threshold

        assertThat(service.needsIngestion(cached, Interval.ONE_DAY)).isFalse();
    }
}
