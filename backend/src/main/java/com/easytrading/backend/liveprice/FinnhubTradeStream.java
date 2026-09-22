package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finnhub's trade stream, feeding {@link LivePriceService} (SCRUM-74).
 *
 * <h3>Why this exists at all</h3>
 *
 * The demo page is built to show a new price every 5 seconds and did not —
 * updates arrived 15 seconds or more apart. Measured, the cause was not our
 * polling: Finnhub's REST {@code /quote} is a <i>stock</i> snapshot endpoint and,
 * pointed at a crypto symbol, refreshes about once per 15 seconds. It returns
 * plausible numbers, which is not the same as current ones. The socket carries
 * ~20 trades a second with a median lag under half a second. Full reasoning and
 * the measurements are in the project decisions log, 2026-09-18.
 *
 * <h3>The shape of it</h3>
 *
 * <b>One connection per server</b>, opened at startup — not one per user and not
 * one per request. That is the same insight the quote cache already encoded, one
 * level deeper: a price is a property of the market, not of who is asking. A
 * hundred open pages cost exactly one subscription.
 *
 * This is also the first thing in the application that is <b>long-lived and
 * stateful</b>. Everything else is request/response: a call arrives, we answer, we
 * forget. A socket exists while nobody is asking for anything, can fail while
 * idle, and delivers data on a thread nobody called — which is why lifecycle,
 * reconnection and threading are all spelled out here rather than left implicit.
 *
 * <h3>What it deliberately does not do</h3>
 *
 * It does not push to the browser, and it does not replace the REST client. The
 * browser keeps polling {@code /api/getLivePrice} — 20 updates a second is more
 * than a chart can show and more than a poll needs — and
 * {@link FinnhubLivePriceClient} stays as the cold-start seed and the fallback
 * while this is reconnecting. That is what makes a dropped socket a degradation
 * rather than an outage.
 *
 * <h3>When it does not start</h3>
 *
 * With no API key, or with {@code liveprice.finnhub.stream-enabled: false}, the
 * stream stays down and everything falls back to the REST quote — the exact
 * behaviour this application had before SCRUM-74. That is what keeps the
 * integration tests deterministic and lets the app still run for someone who has
 * not got a Finnhub key.
 */
@Component
public class FinnhubTradeStream implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FinnhubTradeStream.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final LivePriceService livePriceService;
    private final LiveCandleService liveCandleService;
    private final FinnhubMessageParser parser;
    private final String wsUrl;
    private final String apiKey;
    private final boolean enabled;

    /** "We intend to be connected." Cleared by stop(), and checked before every reconnect. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Consecutive failures, for the backoff. Reset by a message, not by a connect. */
    private final AtomicInteger failures = new AtomicInteger();

    private volatile HttpClient httpClient;
    private volatile ScheduledExecutorService scheduler;
    private volatile WebSocket webSocket;

    public FinnhubTradeStream(LivePriceService livePriceService,
                              LiveCandleService liveCandleService,
                              ObjectMapper objectMapper,
                              @Value("${liveprice.finnhub.ws-url:wss://ws.finnhub.io}") String wsUrl,
                              @Value("${liveprice.finnhub.api-key:}") String apiKey,
                              @Value("${liveprice.finnhub.stream-enabled:true}") boolean enabled) {
        this.livePriceService = livePriceService;
        this.liveCandleService = liveCandleService;
        this.parser = new FinnhubMessageParser(objectMapper);
        this.wsUrl = wsUrl;
        this.apiKey = apiKey;
        this.enabled = enabled;
    }

    // ---- lifecycle -------------------------------------------------------

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (!enabled) {
            log.info("Finnhub trade stream disabled by configuration — live prices will come from the REST quote.");
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("FINNHUB_API_KEY is not set — the trade stream will not start and "
                    + "/api/getLivePrice will fall back to the REST quote (or 503).");
            return;
        }

        httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        // Daemon, so a reconnect pending at shutdown can never hold the JVM open.
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "finnhub-trade-stream");
            thread.setDaemon(true);
            return thread;
        });
        connect();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // running is already false, so the close this triggers will not reconnect.
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutting down");
        }
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("Finnhub trade stream stopped.");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Start last and stop first. The stream depends on the rest of the context
     * being up, and on shutdown there is no point receiving trades for a service
     * that is being torn down.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    // ---- connection ------------------------------------------------------

    private void connect() {
        if (!running.get()) {
            return;
        }
        URI uri = URI.create(wsUrl + (wsUrl.contains("?") ? "&" : "?") + "token=" + apiKey);

        httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(uri, new TradeListener())
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        scheduleReconnect("connect failed: " + error);
                        return;
                    }
                    webSocket = socket;
                    socket.sendText(subscribeMessage(), true);
                    log.info("Finnhub trade stream open; subscribed to {}.", DemoInstrument.FINNHUB_SYMBOL);
                });
    }

    private static String subscribeMessage() {
        return "{\"type\":\"subscribe\",\"symbol\":\"" + DemoInstrument.FINNHUB_SYMBOL + "\"}";
    }

    /**
     * Note what resets the failure count: a <i>message</i>, in {@link #handle},
     * not a successful connect. A socket that opens and immediately closes would
     * otherwise reset the backoff every time and hammer the provider at one
     * attempt per second for ever. Opening is not the same as working.
     */
    private void scheduleReconnect(String reason) {
        if (!running.get()) {
            return;
        }
        webSocket = null;
        Duration delay = ReconnectBackoff.delayFor(failures.getAndIncrement());
        log.warn("Finnhub trade stream is down ({}); reconnecting in {}s.", reason, delay.toSeconds());

        ScheduledExecutorService executor = scheduler;
        if (executor == null) {
            return;
        }
        try {
            executor.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            // stop() won the race. Nothing to recover.
        }
    }

    private void handle(String message) {
        List<LivePrice> trades = parser.parse(message);
        if (trades.isEmpty()) {
            return;   // ping, error, or a batch with nothing usable in it
        }

        // Data actually flowed: this connection works, so the next drop starts
        // its backoff from one second again.
        failures.set(0);

        LivePrice newest = trades.get(0);
        for (LivePrice trade : trades) {
            // EVERY trade goes to the candles, not just the newest: a high or low
            // that only one trade touched is exactly what a candle exists to
            // carry, and keeping only the last of each batch would discard it.
            liveCandleService.acceptStreamedPrice(trade);
            if (trade.timestamp().isAfter(newest.timestamp())) {
                newest = trade;
            }
        }
        // The price readout only ever wants the latest.
        livePriceService.acceptStreamedPrice(newest);
    }

    /**
     * The one piece of this class that is easy to get subtly wrong.
     *
     * {@code onText} receives a FRAGMENT, not a message — {@code last} says
     * whether it completes one — so the pieces must be accumulated. And the socket
     * delivers nothing further until {@code request(1)} is called again. Miss the
     * first and JSON silently corrupts under load; miss the second and the stream
     * stops dead after one message and looks like a provider outage.
     *
     * One listener instance per connection, so the buffer cannot carry a
     * half-message across a reconnect.
     */
    private final class TradeListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(message);
                } catch (RuntimeException ex) {
                    // Never let one bad message kill the connection.
                    log.warn("Failed to handle a Finnhub message: {}", ex.toString());
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            scheduleReconnect("closed " + statusCode + " " + (reason == null || reason.isEmpty() ? "" : reason));
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            scheduleReconnect("error: " + error);
        }
    }
}
