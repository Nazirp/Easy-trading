package com.easytrading.backend.liveprice;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.liveprice.dto.LivePrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-72 — the 4-second cache and its fallback, in isolation.
 *
 * No Spring context and no network: the cache is plain state and the fallback is
 * plain branching, so a fake LivePriceClient that counts calls is enough to pin
 * both down. Crucially it is also DETERMINISTIC — the TTL is set per test rather
 * than slept through, so "the entry expired" is a fact and not a four-second
 * wait that a slow machine could turn into a flake.
 *
 * The HTTP-level behaviour of the same rules (200 with `outdated: true`, the
 * 401s, one upstream call per window over the wire) is in
 * LiveTradingIntegrationTest and LivePriceFallbackIntegrationTest.
 */
class LivePriceServiceTest {

    /** Counts calls and can be told to fail, which is all the double needs to do. */
    private static final class FakeClient implements LivePriceClient {
        final AtomicInteger calls = new AtomicInteger();
        boolean failing;
        BigDecimal price = new BigDecimal("63140.00");

        @Override
        public LivePrice getLivePrice(String finnhubSymbol) {
            calls.incrementAndGet();
            assertThat(finnhubSymbol)
                    .as("the client must be given Finnhub's spelling, not ours")
                    .isEqualTo(DemoInstrument.FINNHUB_SYMBOL);
            if (failing) {
                throw new LivePriceUnavailableException("simulated Finnhub outage");
            }
            return new LivePrice(DemoInstrument.SYMBOL, price, Instant.parse("2026-09-15T12:00:00Z"));
        }
    }

    private static LivePriceService serviceWith(FakeClient client, Duration ttl) {
        return new LivePriceService(client, ttl);
    }

    @Test
    void aSecondCallInsideTheWindowIsServedFromTheCache() {
        FakeClient client = new FakeClient();
        LivePriceService service = serviceWith(client, Duration.ofSeconds(4));

        LiveQuote first = service.currentPrice(DemoInstrument.SYMBOL);
        LiveQuote second = service.currentPrice(DemoInstrument.SYMBOL);

        // This is the whole point of the cache: N callers, one upstream call.
        assertThat(client.calls).hasValue(1);
        assertThat(second.price().price()).isEqualByComparingTo(first.price().price());
        assertThat(first.outdated()).isFalse();
        assertThat(second.outdated()).isFalse();
    }

    @Test
    void anExpiredEntryIsRefetched() {
        FakeClient client = new FakeClient();
        // A zero TTL is "every entry is already expired" -- the expiry branch,
        // without waiting four seconds for it.
        LivePriceService service = serviceWith(client, Duration.ZERO);

        service.currentPrice(DemoInstrument.SYMBOL);
        client.price = new BigDecimal("63999.99");
        LiveQuote second = service.currentPrice(DemoInstrument.SYMBOL);

        assertThat(client.calls).hasValue(2);
        assertThat(second.price().price()).isEqualByComparingTo("63999.99");
        assertThat(second.outdated()).isFalse();
    }

    @Test
    void aFailedCallWithAWarmCacheServesTheLastPriceAsOutdated() {
        FakeClient client = new FakeClient();
        LivePriceService service = serviceWith(client, Duration.ZERO);

        service.currentPrice(DemoInstrument.SYMBOL);   // warms the cache
        client.failing = true;

        LiveQuote quote = service.currentPrice(DemoInstrument.SYMBOL);

        // UC04 6a/6b: the page keeps its price and is told the price is stale.
        assertThat(quote.price().price()).isEqualByComparingTo("63140.00");
        assertThat(quote.outdated()).isTrue();
    }

    @Test
    void aFailedCallWithAColdCacheIsAnUnavailableError() {
        FakeClient client = new FakeClient();
        client.failing = true;
        LivePriceService service = serviceWith(client, Duration.ofSeconds(4));

        // Nothing has ever been fetched, so there is genuinely no price to show.
        // That is a 503, not a made-up number.
        assertThatThrownBy(() -> service.currentPrice(DemoInstrument.SYMBOL))
                .isInstanceOf(LivePriceUnavailableException.class);
    }

    @Test
    void aFailureRecoversOnTheNextCall() {
        FakeClient client = new FakeClient();
        LivePriceService service = serviceWith(client, Duration.ZERO);

        service.currentPrice(DemoInstrument.SYMBOL);
        client.failing = true;
        assertThat(service.currentPrice(DemoInstrument.SYMBOL).outdated()).isTrue();

        client.failing = false;
        client.price = new BigDecimal("64100.00");
        LiveQuote recovered = service.currentPrice(DemoInstrument.SYMBOL);

        // A stale entry does not poison the cache -- the next successful poll
        // replaces it and the "may be outdated" note goes away on its own.
        assertThat(recovered.outdated()).isFalse();
        assertThat(recovered.price().price()).isEqualByComparingTo("64100.00");
    }

    @Test
    void anythingOtherThanTheDemoInstrumentIsRejectedBeforeFinnhubIsCalled() {
        FakeClient client = new FakeClient();
        LivePriceService service = serviceWith(client, Duration.ofSeconds(4));

        assertThatThrownBy(() -> service.currentPrice("EUR/USD"))
                .isInstanceOf(InstrumentNotFoundException.class);

        // Rejected before the provider is touched, so a wrong symbol cannot
        // spend the Finnhub budget.
        assertThat(client.calls).hasValue(0);
    }
}
