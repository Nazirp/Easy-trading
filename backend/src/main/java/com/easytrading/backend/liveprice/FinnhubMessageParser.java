package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one raw Finnhub WebSocket message into the trades it carries.
 *
 * Deliberately a separate class from {@link FinnhubTradeStream}, and deliberately
 * free of any connection state: parsing is pure logic and can therefore be tested
 * by handing it strings, with no socket, no Spring context and no network. The
 * stream owns the connection; this owns the wire format.
 *
 * <h3>The message shapes Finnhub sends</h3>
 *
 * <pre>
 * {"type":"trade","data":[{"s":"BINANCE:BTCUSDT","p":76386.01,"t":1789480000123,"v":0.013}]}
 * {"type":"ping"}
 * {"type":"error","msg":"..."}
 * </pre>
 *
 * <h3>Two things that will bite whoever changes this</h3>
 *
 * <ul>
 *   <li><b>{@code t} here is in MILLISECONDS.</b> The REST quote endpoint's
 *       {@code t} is in SECONDS (see FinnhubLivePriceClient). Same provider, same
 *       field name, different unit — read it as seconds and every live point
 *       lands somewhere in 1970.</li>
 *   <li><b>Anything that is not a trade is not an error.</b> Pings are the
 *       keepalive and arrive routinely; an unparseable message is a bad message,
 *       not a dead connection. Both return an empty list rather than throwing,
 *       because a parse failure must never take the stream down — that would
 *       turn one malformed frame into a reconnect storm.</li>
 * </ul>
 */
public class FinnhubMessageParser {

    private static final Logger log = LoggerFactory.getLogger(FinnhubMessageParser.class);

    private final ObjectMapper objectMapper;

    public FinnhubMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The trades in this message, in the order Finnhub sent them.
     *
     * @return an empty list for a ping, an error, a malformed message, or a trade
     *         batch with nothing usable in it. Never null, never throws.
     */
    public List<LivePrice> parse(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception ex) {
            // A frame we could not read. Log once and carry on; the next frame is
            // along in about 50 milliseconds.
            log.warn("Unreadable message from the Finnhub stream, ignoring it: {}", ex.toString());
            return List.of();
        }

        if (root == null || !root.hasNonNull("type")) {
            return List.of();
        }

        String type = root.get("type").asText();
        if ("error".equals(type)) {
            // Worth a warning rather than silence: a bad token or an unsupported
            // symbol arrives here, and the socket otherwise looks healthy.
            log.warn("Finnhub stream reported an error: {}", root.path("msg").asText("(no message)"));
            return List.of();
        }
        if (!"trade".equals(type)) {
            return List.of();   // ping, and anything they add later
        }

        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }

        List<LivePrice> trades = new ArrayList<>(data.size());
        for (JsonNode trade : data) {
            LivePrice price = toLivePrice(trade);
            if (price != null) {
                trades.add(price);
            }
        }
        return List.copyOf(trades);
    }

    private static LivePrice toLivePrice(JsonNode trade) {
        // We subscribe to one symbol, so this can only fail if Finnhub sends
        // something we did not ask for. Checked anyway, because silently plotting
        // another instrument's price on the BTC/USD chart would be invisible.
        JsonNode symbol = trade.get("s");
        if (symbol != null && !symbol.asText().isEmpty()
                && !DemoInstrument.FINNHUB_SYMBOL.equals(symbol.asText())) {
            return null;
        }

        JsonNode price = trade.get("p");
        if (price == null || !price.isNumber()) {
            return null;
        }
        BigDecimal value = price.decimalValue();
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            // Same rule as the REST client: a price of zero is a failure wearing
            // the shape of a price, never something to plot.
            return null;
        }

        // MILLISECONDS -- see the class javadoc. Missing or zero falls back to now,
        // which is honest at this resolution: the trade reached us just now.
        long millis = trade.path("t").asLong(0L);
        Instant when = millis > 0 ? Instant.ofEpochMilli(millis) : Instant.now();

        // Our own symbol, not Finnhub's, so nothing downstream learns their spelling.
        return new LivePrice(DemoInstrument.SYMBOL, value, when);
    }
}
