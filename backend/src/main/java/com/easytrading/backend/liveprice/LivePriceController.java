package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LiveChartCandle;
import com.easytrading.backend.liveprice.dto.LiveChartResponse;
import com.easytrading.backend.liveprice.dto.LiveHistoryResponse;
import com.easytrading.backend.liveprice.dto.LivePointResponse;
import com.easytrading.backend.liveprice.dto.LivePriceResponse;
import com.easytrading.backend.marketdata.dto.Candle;
import com.easytrading.backend.trading.TradeController;
import com.easytrading.backend.trading.TradeService;
import com.easytrading.backend.user.SessionUser;
import com.easytrading.backend.user.User;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo-trading price endpoints (UC04, SCRUM-72 / SCRUM-76) -- see
 * backend/CONTRACTS.md section 1.
 *
 * <ul>
 *   <li>{@code GET /api/getLiveChart} -- <b>the one the demo page uses.</b> The
 *       entire chart in one response: Twelve Data's history and the live series
 *       merged into one list of 1-minute candles, plus the latest price. Polled
 *       once a second.</li>
 *   <li>{@code GET /api/getLiveHistory} -- legacy. ~30 minutes of 1-minute
 *       candles from Twelve Data, nothing live.</li>
 *   <li>{@code GET /api/getLivePrice} -- legacy. One current price, no chart.</li>
 * </ul>
 *
 * <h3>Why one endpoint replaced two</h3>
 *
 * The page used to fetch the past from {@code getLiveHistory} and the present
 * from {@code getLivePrice} and stitch them together itself. That was two round
 * trips per cycle and, worse, two moments: the readout and the chart were built
 * from prices read at different instants and could disagree on screen. It also
 * pushed the decision of what a candle is into the browser, which sees only about
 * one trade in twenty (see {@link LiveCandleAggregator}) and would therefore draw
 * highs and lows that are too narrow. {@code getLiveChart} answers both questions
 * from one snapshot taken under one lock.
 *
 * The two older endpoints are kept because they are a published contract and
 * still correct, but nothing new should call them; remove them once nothing does.
 *
 * All require a login. Demo trading is account-scoped -- the balance, the
 * positions and the trades that come next all belong to a user -- so the price
 * feed is gated the same way the rest of the page will be, rather than being the
 * one door left open. `symbol` defaults to the demo instrument and anything else
 * is a 404; the parameter exists so the URL stays honest about what it returns
 * and so a second instrument would not change the contract.
 *
 * Errors are mapped centrally in com.easytrading.backend.common.ApiExceptionHandler:
 *   NotAuthenticatedException       -&gt; 401 NOT_AUTHENTICATED
 *   InstrumentNotFoundException     -&gt; 404 NOT_FOUND
 *   LivePriceUnavailableException   -&gt; 503 LIVE_PRICE_UNAVAILABLE
 */
@RestController
public class LivePriceController {

    private final LiveChartService liveChartService;
    private final LivePriceService livePriceService;
    private final TradeService tradeService;
    private final SessionUser sessionUser;

    public LivePriceController(LiveChartService liveChartService,
                               LivePriceService livePriceService,
                               TradeService tradeService,
                               SessionUser sessionUser) {
        this.liveChartService = liveChartService;
        this.livePriceService = livePriceService;
        this.tradeService = tradeService;
        this.sessionUser = sessionUser;
    }

    @GetMapping("/api/getLiveHistory")
    public LiveHistoryResponse getLiveHistory(
            @RequestParam(value = "symbol", defaultValue = DemoInstrument.SYMBOL) String symbol,
            HttpSession session) {
        sessionUser.require(session);

        var points = liveChartService.backfill(symbol).stream()
                .map(LivePriceController::toPoint)
                .toList();

        return new LiveHistoryResponse(DemoInstrument.SYMBOL, points);
    }

    @GetMapping("/api/getLivePrice")
    public LivePriceResponse getLivePrice(
            @RequestParam(value = "symbol", defaultValue = DemoInstrument.SYMBOL) String symbol,
            HttpSession session) {
        sessionUser.require(session);

        LiveQuote quote = livePriceService.currentPrice(symbol);
        return new LivePriceResponse(quote.price().symbol(), quote.price().price(),
                quote.price().timestamp(), quote.outdated());
    }

    @GetMapping("/api/getLiveChart")
    public LiveChartResponse getLiveChart(
            @RequestParam(value = "symbol", defaultValue = DemoInstrument.SYMBOL) String symbol,
            HttpSession session) {
        User user = sessionUser.require(session);

        // ONE assembly, used for the candles AND for the price readout. Asking the
        // service twice could straddle a minute boundary and put a number on
        // screen that the last candle does not agree with.
        LiveChartService.LiveChart chart = liveChartService.chart(symbol);

        // The SAME price then values the position. This is the whole reason the
        // account block lives in this response rather than behind its own endpoint:
        // read separately, the P&L would be computed from a price the chart is not
        // drawing, and the two would disagree on screen by a tick.
        //
        // The controller composes; it decides nothing. Whether a user has a position
        // at all, and what it is worth, are TradeService's questions.
        var account = tradeService.accountFor(user, chart.symbol(), chart.price());

        // An empty candle list is a normal 200: the server has just started and
        // the backfill is unavailable. The page draws nothing and fills in as
        // trades arrive.
        return new LiveChartResponse(chart.symbol(),
                chart.candleSeconds(),
                chart.candles().stream()
                        .map(c -> new LiveChartCandle(c.candle().start(), c.candle().open(),
                                c.candle().high(), c.candle().low(), c.candle().close(),
                                c.live(), c.forming()))
                        .toList(),
                chart.price(),
                chart.priceAt(),
                chart.outdated(),
                TradeController.toResponse(account));
    }

    /**
     * Legacy shape for {@code /api/getLiveHistory}: a zone-less LocalDateTime that
     * means UTC, which is the convention every candle endpoint except the live
     * chart uses (CONTRACTS.md section 2). {@code getLiveChart} returns a real
     * Instant instead, which is why its field is called `start` and not
     * `datetime` -- different name and different type, so neither rule has to be
     * remembered.
     *
     * `price` repeats the close so the original line chart kept working while the
     * frontend moved to candles -- see LivePointResponse.
     */
    private static LivePointResponse toPoint(Candle candle) {
        return new LivePointResponse(candle.datetime(), candle.close(),
                candle.open(), candle.high(), candle.low(), candle.close());
    }
}
