package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * The live half of the demo-trading chart (UC04 step 6, SCRUM-72): one current
 * price for BTC/USD, cached for four seconds.
 *
 * <h3>Why the cache exists</h3>
 *
 * The page polls every 5 seconds, so ONE open page costs ~12 Finnhub requests a
 * minute against a free tier of ~30-60. Three team members demoing at once, with
 * no cache, would be ~36/min and would start getting rate-limited mid-demo. The
 * cache makes the upstream cost depend on time rather than on how many people
 * are watching: N pages polling still cost ~12 calls a minute between them, and
 * nobody sees a price more than four seconds old.
 *
 * Four rather than five so a poll never JUST misses the window. Two polls five
 * seconds apart with a five-second TTL race the clock, and roughly half of them
 * would find the entry expired by a millisecond and call upstream anyway --
 * which is the cache not working, intermittently, which is worse than no cache.
 *
 * <h3>Why it is a field and not a table</h3>
 *
 * The value is worthless four seconds after it is written. Persisting it would
 * mean a write (and a row that is never read again) per upstream call, plus a
 * migration, plus a cleanup job, to store something that expires before a page
 * reload. `price_candle` caches history because history is still true tomorrow;
 * this is not. If the server restarts, the first request repopulates it -- that
 * is the whole recovery story.
 *
 * <h3>One field, one instrument</h3>
 *
 * Demo trading is BTC/USD only ({@link DemoInstrument}), so a single slot is
 * exactly the right size. <b>If a second instrument is ever added this becomes a
 * {@code Map<String, CachedQuote>} keyed by symbol</b> -- everything else here
 * (the TTL, the fallback, the locking) stays as it is.
 */
@Service
public class LivePriceService {

    private static final Logger log = LoggerFactory.getLogger(LivePriceService.class);

    private final LivePriceClient livePriceClient;

    /**
     * How long a fetched quote stays servable. Four seconds in production
     * (application.yml); it is a property only so tests can shrink it to zero
     * and drive the expiry path deterministically instead of sleeping.
     */
    private final Duration cacheTtl;

    /**
     * The whole cache. `volatile` because the reader below runs outside the lock:
     * without it a thread could read a stale or half-published reference on
     * another core. The record itself is immutable, so a reader either sees the
     * previous entry or the new one, never a mix.
     */
    private volatile CachedQuote cached;

    public LivePriceService(LivePriceClient livePriceClient,
                            @Value("${liveprice.cache-ttl:4s}") Duration cacheTtl) {
        this.livePriceClient = livePriceClient;
        this.cacheTtl = cacheTtl;
    }

    /** The cache entry: the price, and the moment it was fetched. Nothing else is needed. */
    private record CachedQuote(LivePrice price, Instant fetchedAt) {}

    /**
     * The current price for the demo instrument.
     *
     * @throws com.easytrading.backend.instrument.InstrumentNotFoundException
     *         the symbol is not the demo instrument
     * @throws LivePriceUnavailableException the provider failed and nothing is cached
     */
    public LiveQuote currentPrice(String symbol) {
        DemoInstrument.requireSupported(symbol);

        CachedQuote hit = cached;
        if (isFresh(hit)) {
            return new LiveQuote(hit.price(), false);
        }
        return refresh(hit);
    }

    /**
     * Synchronized so that a burst of polls arriving together produces ONE
     * upstream call and not one per request -- which is the entire point of the
     * cache, and would otherwise fail exactly when the most people are watching.
     * The first thread in calls Finnhub; the others wait, re-check, and find the
     * value it just stored.
     *
     * Holding a lock across a network call is only safe because that call has a
     * short read timeout (see LivePriceClientConfig). Without one, a hanging
     * Finnhub would park every request thread here.
     */
    private synchronized LiveQuote refresh(CachedQuote seen) {
        CachedQuote hit = cached;
        if (isFresh(hit)) {
            // Somebody else refreshed while this thread waited for the lock.
            return new LiveQuote(hit.price(), false);
        }

        try {
            LivePrice fresh = livePriceClient.getLivePrice(DemoInstrument.FINNHUB_SYMBOL);
            cached = new CachedQuote(fresh, Instant.now());
            return new LiveQuote(fresh, false);
        } catch (RuntimeException ex) {
            // UC04 6a/6b: an unreachable or rate-limited provider must not take
            // the page down. A price a few seconds past its TTL is still a far
            // better answer than an error, as long as we say which it is.
            CachedQuote fallback = hit != null ? hit : seen;
            if (fallback != null) {
                log.warn("Finnhub quote failed, serving the cached price from {}: {}",
                        fallback.fetchedAt(), ex.toString());
                return new LiveQuote(fallback.price(), true);
            }
            // Cold cache: there is genuinely no price to show.
            log.warn("Finnhub quote failed and nothing is cached: {}", ex.toString());
            throw new LivePriceUnavailableException(
                    "The live price feed is unavailable right now. Please try again in a moment.", ex);
        }
    }

    private boolean isFresh(CachedQuote entry) {
        return entry != null
                && Duration.between(entry.fetchedAt(), Instant.now()).compareTo(cacheTtl) < 0;
    }
}
