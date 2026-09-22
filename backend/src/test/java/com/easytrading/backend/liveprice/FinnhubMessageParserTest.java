package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-74 — the Finnhub wire format, with no socket and no Spring context.
 *
 * Parsing is pure logic, so it is tested by handing it strings. That is the whole
 * reason {@link FinnhubMessageParser} is a separate class from the stream: the
 * part most likely to be wrong is also the part that needs no network to check.
 * Payloads below are real shapes captured from the live socket during the spike.
 */
class FinnhubMessageParserTest {

    private final FinnhubMessageParser parser = new FinnhubMessageParser(new ObjectMapper());

    @Test
    void readsPriceAndTimestampFromATrade() {
        List<LivePrice> trades = parser.parse(
                "{\"type\":\"trade\",\"data\":[{\"s\":\"BINANCE:BTCUSDT\",\"p\":76386.01,\"t\":1789480000123,\"v\":0.013}]}");

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).price()).isEqualByComparingTo("76386.01");
        // MILLISECONDS on the socket -- the REST quote's `t` is SECONDS. Reading
        // this one as seconds puts every live point in 1970.
        assertThat(trades.get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1789480000123L));
        // Our own symbol comes back, never Finnhub's spelling.
        assertThat(trades.get(0).symbol()).isEqualTo("BTC/USD");
    }

    @Test
    void readsEveryTradeInABatchInOrder() {
        List<LivePrice> trades = parser.parse("""
                {"type":"trade","data":[
                  {"s":"BINANCE:BTCUSDT","p":76386.01,"t":1789480000123,"v":0.01},
                  {"s":"BINANCE:BTCUSDT","p":76390.55,"t":1789480000456,"v":0.02},
                  {"s":"BINANCE:BTCUSDT","p":76388.20,"t":1789480000789,"v":0.03}
                ]}
                """);

        assertThat(trades).hasSize(3);
        assertThat(trades.get(0).price()).isEqualByComparingTo("76386.01");
        assertThat(trades.get(2).price()).isEqualByComparingTo("76388.20");
    }

    @Test
    void aPingIsNotATradeAndNotAnError() {
        // The keepalive, which arrives routinely. Silence is the correct response.
        assertThat(parser.parse("{\"type\":\"ping\"}")).isEmpty();
    }

    @Test
    void anErrorMessageYieldsNoTrades() {
        assertThat(parser.parse("{\"type\":\"error\",\"msg\":\"Invalid symbol\"}")).isEmpty();
    }

    @Test
    void malformedJsonIsIgnoredRatherThanThrown() {
        // One bad frame must never propagate: the stream would treat the exception
        // as a dead connection and start reconnecting over a single typo.
        assertThat(parser.parse("{\"type\":\"trade\",\"data\":[{\"p\":76")).isEmpty();
        assertThat(parser.parse("not json at all")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void tradesWithoutAUsablePriceAreDropped() {
        List<LivePrice> trades = parser.parse("""
                {"type":"trade","data":[
                  {"s":"BINANCE:BTCUSDT","t":1789480000123,"v":0.01},
                  {"s":"BINANCE:BTCUSDT","p":0,"t":1789480000456,"v":0.02},
                  {"s":"BINANCE:BTCUSDT","p":76390.55,"t":1789480000789,"v":0.03}
                ]}
                """);

        // Same rule as the REST client: a price of zero is a failure wearing the
        // shape of a price. One bad entry does not discard the good ones beside it.
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).price()).isEqualByComparingTo("76390.55");
    }

    @Test
    void anotherInstrumentsTradeIsIgnored() {
        // We only ever subscribe to one symbol, so this should be unreachable --
        // which is exactly why it is worth asserting: if it ever happens, plotting
        // Apple's price on the BTC/USD chart would be silent and invisible.
        assertThat(parser.parse(
                "{\"type\":\"trade\",\"data\":[{\"s\":\"AAPL\",\"p\":231.10,\"t\":1789480000123,\"v\":5}]}"))
                .isEmpty();
    }

    @Test
    void aMissingTimestampFallsBackToNow() {
        Instant before = Instant.now().minusSeconds(1);

        List<LivePrice> trades = parser.parse(
                "{\"type\":\"trade\",\"data\":[{\"s\":\"BINANCE:BTCUSDT\",\"p\":76386.01,\"v\":0.013}]}");

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).timestamp()).isAfter(before);
    }
}
