package com.easytrading.backend.liveprice;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.liveprice.dto.LivePrice;
import com.easytrading.backend.marketdata.MarketDataClient;
import com.easytrading.backend.marketdata.dto.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-76 — joining Twelve Data's history to the live series.
 *
 * No Spring and no WireMock: the only collaborator is a MarketDataClient, and a
 * hand-written fake can also <i>count its calls</i>, which is the point of half
 * of these tests. The quota rule ("one fetch per process, not one per poll") is
 * not a performance preference — at one poll a second, getting it wrong burns
 * the day's 800 requests in about fourteen minutes and the chart goes blank for
 * everyone until midnight UTC. So it is asserted, not trusted.
 *
 * The fixture is dated in the PAST on purpose: the aggregator seals a minute that
 * the wall clock has passed, so a future-dated fixture would leave every candle
 * permanently "forming" and quietly invert the assertions about it.
 */
class LiveChartServiceTest {

    private static final long ONE_MINUTE = 60_000L;
    private static final Instant BASE = Instant.parse("2026-09-01T09:00:00Z");

    private final FakeMarketData marketData = new FakeMarketData();
    private final LiveCandleService liveCandles = new LiveCandleService(Duration.ofSeconds(6));
    private final LiveChartService chartService = new LiveChartService(marketData, liveCandles);

    /** Counts calls, which is what a Mockito verify() would give us plus the arguments. */
    private static final class FakeMarketData implements MarketDataClient {
        int calls;
        boolean failing;
        String lastInterval;
        int lastOutputSize;
        List<Candle> candles = new ArrayList<>();

        @Override
        public List<Candle> getCandles(String symbol, String interval, int outputSize) {
            calls++;
            lastInterval = interval;
            lastOutputSize = outputSize;
            if (failing) {
                throw new IllegalStateException("Twelve Data is down");
            }
            return candles;
        }
    }

    /** Twelve Data answers newest-first with a zone-less LocalDateTime that means UTC. */
    private static Candle history(long minutesAfterBase, String open, String high, String low, String close) {
        return new Candle(LocalDateTime.ofInstant(BASE.plusSeconds(minutesAfterBase * 60), ZoneOffset.UTC),
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 1L);
    }

    private void streamTrade(String price, Instant at) {
        liveCandles.acceptStreamedPrice(new LivePrice(DemoInstrument.SYMBOL, new BigDecimal(price), at));
    }

    private void givenThreeMinutesOfHistory() {
        marketData.candles = List.of(
                history(2, "102", "103", "101", "102.5"),
                history(1, "101", "102", "100", "102"),
                history(0, "100", "101", "99", "101"));
    }

    // ---- history alone ---------------------------------------------------

    @Test
    void withNoLiveDataTheChartIsTheBackfillOldestFirst() {
        givenThreeMinutesOfHistory();

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);

        assertThat(chart.candles()).hasSize(3);
        assertThat(chart.candles().get(0).candle().start()).isEqualTo(BASE);
        assertThat(chart.candles()).noneMatch(LiveChartService.ChartCandle::live);
        assertThat(chart.candles()).noneMatch(LiveChartService.ChartCandle::forming);
        assertThat(marketData.lastInterval).isEqualTo("1min");
    }

    @Test
    void theReadoutIsTheLastCandlesCloseSoTheNumberAndTheChartCannotDisagree() {
        givenThreeMinutesOfHistory();

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);

        assertThat(chart.price()).isEqualByComparingTo("102.5");
        // With no trades at all, the newest thing we know is the CLOSE of the last
        // backfilled minute, which was true at the end of it -- saying "start"
        // would understate the age of the number by a minute.
        assertThat(chart.priceAt()).isEqualTo(BASE.plusSeconds(180));
        assertThat(chart.outdated()).isTrue();
        assertThat(chart.candleSeconds()).isEqualTo(60);
    }

    @Test
    void theBackfillIsFetchedOncePerProcessNotOncePerPoll() {
        givenThreeMinutesOfHistory();

        for (int i = 0; i < 20; i++) {
            chartService.chart(DemoInstrument.SYMBOL);
        }

        // 20 polls is 20 seconds of one page being open. At one fetch per poll the
        // day's 800-request budget would be gone before lunch.
        assertThat(marketData.calls).isEqualTo(1);
        assertThat(marketData.lastOutputSize).isEqualTo(LiveChartService.BACKFILL_CANDLES);
    }

    // ---- the merge -------------------------------------------------------

    @Test
    void aLiveCandleReplacesTheBackfilledOneForTheSameMinute() {
        givenThreeMinutesOfHistory();
        streamTrade("555", BASE.plusSeconds(90));   // minute 1, which history also covers

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);

        assertThat(chart.candles()).hasSize(3);     // not four -- merged by minute
        LiveChartService.ChartCandle minuteOne = chart.candles().get(1);
        assertThat(minuteOne.candle().close()).isEqualByComparingTo("555");
        assertThat(minuteOne.live()).isTrue();
        // The live candle saw every trade in that minute; the backfilled one is a
        // provider's summary. When they disagree the one that saw everything wins.
        assertThat(chart.candles().get(0).live()).isFalse();
        assertThat(chart.candles().get(0).candle().close()).isEqualByComparingTo("101");
    }

    @Test
    void theLivePriceAndItsTimestampComeFromTheStreamOnceItIsRunning() {
        givenThreeMinutesOfHistory();
        streamTrade("555", BASE.plusSeconds(90));

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);

        assertThat(chart.priceAt()).isEqualTo(BASE.plusSeconds(90));
    }

    @Test
    void onlyTheCandleStillBeingBuiltIsMarkedForming() {
        givenThreeMinutesOfHistory();
        // A trade in the current minute, so this candle really is still forming.
        streamTrade("777", Instant.now());

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);
        List<LiveChartService.ChartCandle> candles = chart.candles();

        assertThat(candles.stream().filter(LiveChartService.ChartCandle::forming).count()).isEqualTo(1);
        assertThat(candles.get(candles.size() - 1).forming()).isTrue();
        assertThat(chart.outdated()).isFalse();
        assertThat(chart.price()).isEqualByComparingTo("777");
    }

    @Test
    void onceTheLiveSeriesFillsTheWindowTwelveDataIsNotAskedAtAll() {
        givenThreeMinutesOfHistory();
        Instant start = Instant.now().minusSeconds(40 * 60);
        for (int i = 0; i < 40; i++) {
            streamTrade(String.valueOf(200 + i), start.plusMillis(i * ONE_MINUTE));
        }

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);

        // The steady state after half an hour of uptime: the backfill has nothing
        // left to contribute, so it is never fetched in the first place.
        assertThat(marketData.calls).isZero();
        assertThat(chart.candles()).hasSize(liveCandles.windowCandles());
        assertThat(chart.candles()).allMatch(LiveChartService.ChartCandle::live);
    }

    @Test
    void theChartIsTrimmedToTheWindow() {
        List<Candle> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(history(i, "100", "100", "100", "100"));
        }
        marketData.candles = List.copyOf(many);

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);

        assertThat(chart.candles()).hasSize(liveCandles.windowCandles());
        // ...and it is the RECENT end that survives.
        assertThat(chart.candles().get(chart.candles().size() - 1).candle().start())
                .isEqualTo(BASE.plusSeconds(99 * 60));
    }

    // ---- failure ---------------------------------------------------------

    @Test
    void aDeadProviderGivesAnEmptyChartRatherThanAnError() {
        marketData.failing = true;

        LiveChartService.LiveChart chart = chartService.chart(DemoInstrument.SYMBOL);

        // UC04 extension 5a: a missing past is a degraded chart, never a blocked
        // page. The chart fills itself in from the stream.
        assertThat(chart.candles()).isEmpty();
        assertThat(chart.price()).isNull();
        assertThat(chart.priceAt()).isNull();
        assertThat(chart.outdated()).isTrue();
    }

    @Test
    void aFailedBackfillIsNotRetriedOnEveryPoll() {
        marketData.failing = true;

        for (int i = 0; i < 20; i++) {
            chartService.chart(DemoInstrument.SYMBOL);
        }

        // An outage must not turn a once-a-second poll into a once-a-second retry.
        assertThat(marketData.calls).isEqualTo(1);
    }

    @Test
    void aFailedBackfillIsEventuallyRetried() {
        marketData.failing = true;
        chartService.chart(DemoInstrument.SYMBOL);
        assertThat(marketData.calls).isEqualTo(1);

        // Simulate the back-off window having passed, rather than sleeping for it.
        chartService.forgetBackfill();
        marketData.failing = false;
        givenThreeMinutesOfHistory();

        assertThat(chartService.chart(DemoInstrument.SYMBOL).candles()).hasSize(3);
        assertThat(marketData.calls).isEqualTo(2);
    }

    @Test
    void anEmptyAnswerCountsAsAFailureRatherThanAsAnEmptyHistoryHeldForever() {
        marketData.candles = List.of();

        chartService.chart(DemoInstrument.SYMBOL);
        chartService.forgetBackfill();
        givenThreeMinutesOfHistory();

        // Holding "no data" forever would mean one unlucky moment at startup
        // costs the chart its history for the life of the process.
        assertThat(chartService.chart(DemoInstrument.SYMBOL).candles()).hasSize(3);
    }

    // ---- the symbol guard ------------------------------------------------

    @Test
    void anythingButTheDemoInstrumentIsA404() {
        assertThatThrownBy(() -> chartService.chart("EUR/USD"))
                .isInstanceOf(InstrumentNotFoundException.class);
        assertThat(marketData.calls).isZero();
    }

    @Test
    void theLegacyBackfillEndpointIsNotAffectedByTheHeldHistory() {
        givenThreeMinutesOfHistory();

        chartService.backfill(DemoInstrument.SYMBOL);
        chartService.backfill(DemoInstrument.SYMBOL);

        // backfill() is called once per page load, not once a second, so it has
        // none of the quota problem chart() has and fetches fresh every time.
        assertThat(marketData.calls).isEqualTo(2);
    }
}
