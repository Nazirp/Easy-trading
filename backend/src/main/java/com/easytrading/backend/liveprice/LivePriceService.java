package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * One current price for
 * BTC/USD, held in memory and handed to anyone who asks.
 *
 * <h3>Where the price comes from</h3>
 *
 * {@link FinnhubTradeStream} pushes every trade
 * via {@link #acceptStreamedPrice}, and reads are served
 * from memory with no upstream call at all.
 *
 * A price
 * younger than {@link #maxPriceAge} means trades are flowing; an older one means
 * the socket has stalled, is reconnecting, or never started — and that is exactly
 * when the REST quote earns its keep as a fallback.
 *
 * <h3>Why it is a field and not a table</h3>
 *
 * The value is worthless seconds after it is written, so persisting it would mean
 * a row per trade that is never read again. A restart refills it from
 * the stream within a second.
 *
 * <h3>One field, one instrument</h3>
 *
 * Demo trading is BTC/USD only ({@link DemoInstrument}), so a single slot is
 * exactly the right size.
 */
@Service
public class LivePriceService {

    private static final Logger log = LoggerFactory.getLogger(LivePriceService.class);

    private final LivePriceClient livePriceClient;

    /**
     * How old the held price may be before we stop trusting the stream and try the
     * REST quote instead.
     *
     * A property only so that tests can shrink it to zero and drive the fallback
     * path deterministically instead of sleeping.
     */
    private final Duration maxPriceAge;

    /**
     * The whole cache. {@code volatile} because the stream thread writes it while
     * HTTP threads read it outside any lock: without it a reader could see a stale
     * or half-published reference on another core. The record is immutable, so a
     * reader sees either the previous entry or the new one, never a mix.
     */
    private volatile CachedQuote cached;

    public LivePriceService(LivePriceClient livePriceClient,
                            @Value("${liveprice.max-price-age:6s}") Duration maxPriceAge) {
        this.livePriceClient = livePriceClient;
        this.maxPriceAge = maxPriceAge;
    }

    /** The held price, and the moment it arrived. */
    private record CachedQuote(LivePrice price, Instant fetchedAt) {}

    /**
     * Called by {@link FinnhubTradeStream} for every trade it receives — roughly
     * twenty times a second.
     *
     * An unconditional write, and safe as one: trades arrive newest-last on a
     * single stream thread, so there is no ordering to protect. The only value
     * that could overtake a trade is a REST answer that was already in flight, and
     * {@link #refresh} is the side that checks for that.
     *
     * Deliberately does no work beyond the assignment. It runs on the socket's
     * callback thread, where anything slow would stall delivery of the next
     * message.
     */
    public void acceptStreamedPrice(LivePrice price) {
        if (price == null) {
            return;
        }
        cached = new CachedQuote(price, Instant.now());
    }

    /**
     * The current price for the demo instrument.
     *
     * In normal running this is a volatile read and nothing else — the stream has
     * put a price there within the last fraction of a second.
     *
     * @throws com.easytrading.backend.instrument.InstrumentNotFoundException
     *         the symbol is not the demo instrument
     * @throws LivePriceUnavailableException nothing is held and the fallback failed
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
     * The fallback path: the stream has not produced a price recently, so ask the
     * REST quote. Reached on a cold start before the first trade arrives, while the
     * socket is reconnecting, and whenever no key or configuration has let the
     * stream start at all.
     *
     * Synchronized so that a burst of polls arriving together produces one upstream
     * call rather than one per request. Holding a lock across a network call is only
     * safe because that call has a short read timeout (see LivePriceClientConfig);
     * without one, a hanging Finnhub would park every request thread here.
     */
    private synchronized LiveQuote refresh(CachedQuote seen) {
        CachedQuote hit = cached;
        if (isFresh(hit)) {
            // A trade arrived, or another thread refreshed, while this one waited.
            return new LiveQuote(hit.price(), false);
        }

        try {
            LivePrice fresh = livePriceClient.getLivePrice(DemoInstrument.FINNHUB_SYMBOL);

            // Re-check before storing. A REST call can take a second or two, and the
            // stream may have delivered something newer in the meantime — writing the
            // REST answer over it would step the price backwards in time.
            CachedQuote current = cached;
            if (isFresh(current)) {
                return new LiveQuote(current.price(), false);
            }

            cached = new CachedQuote(fresh, Instant.now());
            return new LiveQuote(fresh, false);
        } catch (RuntimeException ex) {
            // An unreachable or rate-limited provider must not take the
            // page down. A price a few seconds past its threshold is a far better
            // answer than an error, as long as we say which it is.
            CachedQuote fallback = hit != null ? hit : seen;
            if (fallback != null) {
                log.warn("Live price fallback failed, serving the price held since {}: {}",
                        fallback.fetchedAt(), ex.toString());
                return new LiveQuote(fallback.price(), true);
            }
            log.warn("Live price fallback failed and nothing is held: {}", ex.toString());
            throw new LivePriceUnavailableException(
                    "The live price feed is unavailable right now. Please try again in a moment.", ex);
        }
    }

    private boolean isFresh(CachedQuote entry) {
        return entry != null
                && Duration.between(entry.fetchedAt(), Instant.now()).compareTo(maxPriceAge) < 0;
    }
}
