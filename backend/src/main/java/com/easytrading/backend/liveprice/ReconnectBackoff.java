package com.easytrading.backend.liveprice;

import java.time.Duration;

/**
 * How long to wait before the next attempt to reopen the Finnhub stream
 * (SCRUM-74): 1s, 2s, 4s, 8s, 16s, then 30s for ever.
 *
 * <b>A dropped socket is normal operation, not an exception.</b> Connections are
 * closed by idle timeouts, proxies, laptop sleep and provider restarts; a stream
 * that has never reconnected has simply not run long enough yet. So the question
 * is not whether to reconnect but how eagerly.
 *
 * Doubling answers both halves of that. A one-off drop is recovered in a second,
 * which a user polling every five seconds never even notices. A provider that is
 * genuinely down is retried twice a minute rather than 3,600 times an hour —
 * reconnecting hard at a service that is already struggling is how a client turns
 * an outage into a longer outage, and how an API key gets rate-limited for
 * connection attempts rather than data.
 *
 * The cap matters as much as the growth: without it, a night-long outage would
 * back off to hours and the demo would still be dead ten minutes after the
 * provider recovered. Thirty seconds is the longest anyone should have to wait
 * once Finnhub is healthy again.
 *
 * Pure arithmetic with no clock and no state, so the schedule can be asserted in
 * a unit test instead of being watched in a log.
 */
public final class ReconnectBackoff {

    /** First retry — fast enough that an ordinary blip is invisible to the page. */
    static final Duration INITIAL = Duration.ofSeconds(1);

    /** Never wait longer than this, however long the outage has lasted. */
    static final Duration MAX = Duration.ofSeconds(30);

    /** Doubling past this many attempts would only overshoot MAX. */
    private static final int MAX_DOUBLINGS = 5;

    private ReconnectBackoff() {
    }

    /**
     * @param attempt how many attempts have already failed; 0 for the first retry
     * @return 1s, 2s, 4s, 8s, 16s, 30s, 30s, … — never below INITIAL, never above MAX
     */
    public static Duration delayFor(int attempt) {
        if (attempt <= 0) {
            return INITIAL;
        }
        long seconds = INITIAL.toSeconds() << Math.min(attempt, MAX_DOUBLINGS);
        return seconds >= MAX.toSeconds() ? MAX : Duration.ofSeconds(seconds);
    }
}
