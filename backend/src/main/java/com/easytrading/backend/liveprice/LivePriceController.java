package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LiveHistoryResponse;
import com.easytrading.backend.liveprice.dto.LivePointResponse;
import com.easytrading.backend.liveprice.dto.LivePriceResponse;
import com.easytrading.backend.marketdata.dto.Candle;
import com.easytrading.backend.user.SessionUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints the demo-trading chart is built from (UC04, SCRUM-72) --
 * see backend/CONTRACTS.md section 1.
 *
 * <ul>
 *   <li>{@code GET /api/getLiveHistory} -- the chart's starting state, ~30
 *       minutes of 1-minute closes from Twelve Data. Called once, when the page
 *       opens.</li>
 *   <li>{@code GET /api/getLivePrice} -- one current price from Finnhub. Called
 *       every 5 seconds for as long as the page is open and visible.</li>
 * </ul>
 *
 * Two endpoints rather than one because they have nothing in common but the
 * axis: different provider, different cadence, different shape (candle closes
 * vs. a single quote), different failure behaviour. Folding them together would
 * mean re-fetching 30 minutes of history every five seconds.
 *
 * Both require a login. Demo trading is account-scoped -- the balance, the
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
    private final SessionUser sessionUser;

    public LivePriceController(LiveChartService liveChartService,
                               LivePriceService livePriceService,
                               SessionUser sessionUser) {
        this.liveChartService = liveChartService;
        this.livePriceService = livePriceService;
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

    /** Only the close survives: the live tail is a line of single prices, so the past must be one too. */
    private static LivePointResponse toPoint(Candle candle) {
        return new LivePointResponse(candle.datetime(), candle.close());
    }
}
