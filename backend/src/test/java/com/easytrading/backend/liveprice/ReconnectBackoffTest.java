package com.easytrading.backend.liveprice;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconnect schedule, asserted rather than watched in a log.
 */
class ReconnectBackoffTest {

    @Test
    void doublesFromOneSecond() {
        assertThat(ReconnectBackoff.delayFor(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ReconnectBackoff.delayFor(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(ReconnectBackoff.delayFor(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(ReconnectBackoff.delayFor(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(ReconnectBackoff.delayFor(4)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void capsAtThirtySecondsAndStaysThere() {
        // Without a cap, a night-long outage backs off to hours and the demo is
        // still dead ten minutes after Finnhub recovers.
        assertThat(ReconnectBackoff.delayFor(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ReconnectBackoff.delayFor(20)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ReconnectBackoff.delayFor(10_000)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void neverReturnsZeroOrNegative() {
        // A zero delay would be a reconnect storm, and the caller passes a counter
        // that could in principle overflow past Integer.MAX_VALUE.
        assertThat(ReconnectBackoff.delayFor(-1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ReconnectBackoff.delayFor(Integer.MIN_VALUE)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ReconnectBackoff.delayFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theWholeScheduleStaysWithinBounds() {
        for (int attempt = 0; attempt < 500; attempt++) {
            Duration delay = ReconnectBackoff.delayFor(attempt);
            assertThat(delay).isBetween(ReconnectBackoff.INITIAL, ReconnectBackoff.MAX);
        }
    }
}
