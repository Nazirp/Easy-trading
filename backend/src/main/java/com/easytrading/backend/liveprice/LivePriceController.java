package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LiveChartCandle;
import com.easytrading.backend.liveprice.dto.LiveChartResponse;
import com.easytrading.backend.trading.TradeController;
import com.easytrading.backend.trading.TradeService;
import com.easytrading.backend.user.SessionUser;
import com.easytrading.backend.user.User;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo-trading price endpoint -- see
 * backend/CONTRACTS.md.
 *
 * {@code GET /api/getLiveChart} -- the
 * entire chart in one response: Twelve Data's history and the live series
 * merged into one list of 1-minute candles, plus the latest price. Polled
 * once a second.
 *
 * Demo trading is account-scoped -- the balance and the trades belong to a user -- so the
 * price feed is gated the same way as the rest of the page, rather than being the one
 * door left open. `symbol` defaults to the demo instrument and anything else is a 404;
 * the parameter exists so the URL stays honest about what it returns.
 *
 * Errors are mapped centrally in com.easytrading.backend.common.ApiExceptionHandler:
 *   NotAuthenticatedException       -&gt; 401 NOT_AUTHENTICATED
 *   InstrumentNotFoundException     -&gt; 404 NOT_FOUND
 *   LivePriceUnavailableException   -&gt; 503 LIVE_PRICE_UNAVAILABLE
 */
@RestController
public class LivePriceController {

    private final LiveChartService liveChartService;
    private final TradeService tradeService;
    private final SessionUser sessionUser;

    public LivePriceController(LiveChartService liveChartService,
                               TradeService tradeService,
                               SessionUser sessionUser) {
        this.liveChartService = liveChartService;
        this.tradeService = tradeService;
        this.sessionUser = sessionUser;
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

        // The SAME price then values the open trades. This is the whole reason the
        // account block lives in this response rather than behind its own endpoint:
        // read separately, the P&L would be computed from a price the chart is not
        // drawing, and the two would disagree on screen by a tick.
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
}
