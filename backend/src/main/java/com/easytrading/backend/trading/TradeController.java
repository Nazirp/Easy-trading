package com.easytrading.backend.trading;

import com.easytrading.backend.liveprice.DemoInstrument;
import com.easytrading.backend.trading.dto.AccountResponse;
import com.easytrading.backend.trading.dto.PlaceTradeRequest;
import com.easytrading.backend.trading.dto.PlaceTradeResponse;
import com.easytrading.backend.trading.dto.TradeHistoryResponse;
import com.easytrading.backend.trading.dto.TradeResponse;
import com.easytrading.backend.user.SessionUser;
import com.easytrading.backend.user.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * Placing and listing simulated trades (UC04 steps 8-11, SCRUM-79) -- see
 * backend/CONTRACTS.md section 1.
 *
 * <ul>
 *   <li>{@code POST /api/trades} -- place a buy or sell. 201 with the executed trade
 *       and the account it produced.</li>
 *   <li>{@code GET /api/trades} -- this user's history for the instrument, newest
 *       first.</li>
 * </ul>
 *
 * Both start with {@code sessionUser.require(session)}, which is the single
 * definition of "logged in" and the single source of the 401. <b>Neither takes a user
 * id</b>, so no caller can name a different one -- the identity is structurally
 * unforgeable rather than merely checked.
 *
 * The controller holds no rules: it reads the request, calls the service, maps to a
 * response shape and picks a status code. Every decision about whether a trade is
 * allowed lives in {@link TradeService}, and every error is translated centrally in
 * {@code common.ApiExceptionHandler}:
 *
 * <pre>
 *   NotAuthenticatedException       -&gt; 401 NOT_AUTHENTICATED
 *   InstrumentNotFoundException     -&gt; 404 NOT_FOUND
 *   InvalidTradeException           -&gt; 400 INVALID_BODY
 *   InsufficientFundsException      -&gt; 409 INSUFFICIENT_FUNDS
 *   InsufficientPositionException   -&gt; 409 INSUFFICIENT_POSITION
 *   LivePriceUnavailableException   -&gt; 503 LIVE_PRICE_UNAVAILABLE
 * </pre>
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
    public ResponseEntity<PlaceTradeResponse> place(@RequestBody PlaceTradeRequest request,
                                                    HttpSession session) {
        User user = sessionUser.require(session);

        // A null body symbol falls back to the demo instrument, exactly like the
        // query parameter on the chart endpoints -- anything else is a 404 rather
        // than a silent redirect, so a frontend bug is visible instead of trading
        // the wrong thing.
        String symbol = request.symbol() == null ? DemoInstrument.SYMBOL : request.symbol();

        TradeService.TradeResult result =
                tradeService.execute(user, symbol, request.side(), request.quantity());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PlaceTradeResponse(toResponse(result.trade()), toResponse(result.account())));
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

    /**
     * The one place a stored {@code LocalDateTime} becomes an {@code Instant}.
     *
     * The column is {@code TIMESTAMP} and holds UTC by the schema's convention, so
     * the entity mirrors it as a zone-less value. The API's rule is the other one --
     * a moment carries a zone -- so the conversion happens here, at the boundary,
     * rather than being left for the frontend to guess at. Getting this wrong is what
     * put intraday candles on six different clocks in September.
     */
    private static TradeResponse toResponse(Trade trade) {
        return new TradeResponse(trade.getId(), trade.getSymbol(), trade.getSide().name(),
                trade.getQuantity(), trade.getPrice(),
                trade.getExecutedAt().toInstant(ZoneOffset.UTC));
    }

    /** Null in, null out: a user with no trades has no account block, not an empty one. */
    public static AccountResponse toResponse(AccountView account) {
        if (account == null) {
            return null;
        }
        return new AccountResponse(account.cash(), account.quantity(), account.averageCost(),
                account.marketValue(), account.unrealisedPnl(), account.unrealisedPnlPercent());
    }
}
