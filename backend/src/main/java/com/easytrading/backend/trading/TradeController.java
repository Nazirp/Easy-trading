package com.easytrading.backend.trading;

import com.easytrading.backend.liveprice.DemoInstrument;
import com.easytrading.backend.trading.dto.AccountResponse;
import com.easytrading.backend.trading.dto.OpenTradeRequest;
import com.easytrading.backend.trading.dto.TradeAndAccountResponse;
import com.easytrading.backend.trading.dto.TradeHistoryResponse;
import com.easytrading.backend.trading.dto.TradeResponse;
import com.easytrading.backend.user.SessionUser;
import com.easytrading.backend.user.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Opening, closing and listing simulated trades -- see
 * backend/CONTRACTS.md.
 *
 * <ul>
 *   <li>{@code POST /api/trades} -- open a LONG or SHORT. 201 with the trade and the
 *       account it produced.</li>
 *   <li>{@code POST /api/trades/{id}/close} -- close one, whole. 200 with the closed
 *       trade and the account.</li>
 *   <li>{@code GET /api/trades} -- this user's trades, newest first.</li>
 * </ul>
 *
 * Every method starts with {@code sessionUser.require(session)} and none takes a user
 * id, so no caller can name a different user. The controller holds no rules: it reads
 * the request, calls the service, maps the result and picks a status. Errors are
 * translated centrally in {@code common.ApiExceptionHandler}:
 *
 * <pre>
 *   NotAuthenticatedException       -&gt; 401 NOT_AUTHENTICATED
 *   InstrumentNotFoundException     -&gt; 404 NOT_FOUND
 *   TradeNotFoundException          -&gt; 404 NOT_FOUND
 *   InvalidTradeException           -&gt; 400 INVALID_BODY
 *   InsufficientFundsException      -&gt; 409 INSUFFICIENT_FUNDS
 *   TradeAlreadyClosedException     -&gt; 409 TRADE_ALREADY_CLOSED
 *   LivePriceUnavailableException   -&gt; 503 LIVE_PRICE_UNAVAILABLE
 * </pre>
 *
 * The close takes a numeric id in the path. The query-parameter rule used elsewhere
 * exists because <i>symbols</i> contain slashes; it was never a rule against paths.
 */
@RestController
public class TradeController {

    private final TradeService tradeService;
    private final SessionUser sessionUser;

    public TradeController(TradeService tradeService, SessionUser sessionUser) {
        this.tradeService = tradeService;
        this.sessionUser = sessionUser;
    }

    @PostMapping("/api/trades")
    public ResponseEntity<TradeAndAccountResponse> open(@RequestBody OpenTradeRequest request,
                                                        HttpSession session) {
        User user = sessionUser.require(session);

        // A missing symbol falls back to the demo instrument, like the query parameter
        // on the chart; anything else is a 404 rather than a silent redirect, so a
        // frontend bug is visible instead of trading the wrong thing.
        String symbol = request.symbol() == null ? DemoInstrument.SYMBOL : request.symbol();

        TradeService.TradeResult result =
                tradeService.open(user, symbol, request.direction(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @PostMapping("/api/trades/{id}/close")
    public TradeAndAccountResponse close(@PathVariable("id") Long id, HttpSession session) {
        User user = sessionUser.require(session);
        return toResponse(tradeService.close(user, id));
    }

    @GetMapping("/api/trades")
    public TradeHistoryResponse history(
            @RequestParam(value = "symbol", defaultValue = DemoInstrument.SYMBOL) String symbol,
            HttpSession session) {
        User user = sessionUser.require(session);

        var trades = tradeService.history(user.getId(), symbol).stream()
                .map(TradeController::toResponse)
                .toList();
        return new TradeHistoryResponse(DemoInstrument.SYMBOL, trades);
    }

    // ---- mapping ---------------------------------------------------------------

    /**
     * Also used by {@code LivePriceController}, which composes the same account block
     * into {@code /api/getLiveChart} -- one mapping, so the block cannot differ between
     * the two responses that carry it.
     */
    public static AccountResponse toResponse(AccountView account) {
        return new AccountResponse(account.cash(), account.margin(), account.equity(),
                account.unrealisedPnl(), account.realisedPnl(),
                account.openTrades().stream().map(TradeController::toResponse).toList());
    }

    private static TradeAndAccountResponse toResponse(TradeService.TradeResult result) {
        return new TradeAndAccountResponse(toResponse(result.trade()), toResponse(result.account()));
    }

    private static TradeResponse toResponse(PricedTrade priced) {
        Trade trade = priced.trade();
        return new TradeResponse(trade.getId(), trade.getSymbol(), trade.getDirection().name(),
                trade.getQuantity(), trade.getEntryPrice(), instant(trade.getOpenedAt()),
                trade.getExitPrice(), instant(trade.getClosedAt()),
                priced.pnl(), priced.pnlPercent());
    }

    /**
     * The one place a stored {@code LocalDateTime} becomes an {@code Instant}. The
     * columns are {@code TIMESTAMP} holding UTC by the schema's convention; the API's
     * rule is that a moment carries a zone, so the conversion happens here rather than
     * being left for the frontend to guess at.
     */
    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
