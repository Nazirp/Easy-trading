package com.easytrading.backend.trading;

import com.easytrading.backend.liveprice.DemoInstrument;
import com.easytrading.backend.liveprice.LivePriceService;
import com.easytrading.backend.liveprice.LivePriceUnavailableException;
import com.easytrading.backend.liveprice.LiveQuote;
import com.easytrading.backend.user.User;
import com.easytrading.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Placing simulated trades, and working out what the account looks like afterwards
 * (UC04, SCRUM-79).
 *
 * <h3>The execution price comes from here, never from the caller</h3>
 *
 * {@link #execute} takes a symbol, a side and a quantity. It does <b>not</b> take a
 * price, and a {@code price} field in the request body is ignored. The natural design
 * is for the page -- which is already displaying the price -- to post it back, and it
 * is wrong in a way that is not subtle: a request body is whatever the caller chooses
 * to type, so {@code {"price": 1}} buys a Bitcoin for a dollar. It does not even take
 * malice; a tab left open for five minutes would post a five-minute-old price in
 * perfect good faith and be filled at it.
 *
 * So the server reads its own last known price. The client says <i>what</i> and
 * <i>how much</i>, never <i>at what price</i>. This is the trading instance of a rule
 * the codebase already follows twice -- the frontend does not remember who is logged
 * in, and no endpoint takes a user id as a parameter. Stated generally: <b>anything
 * the server can determine, the server determines.</b>
 *
 * <h3>An unknown or stale price is refused, not guessed</h3>
 *
 * If the stream is down and the fallback has nothing current, the answer is a 503
 * rather than a fill at whatever was last seen. A refused trade is an inconvenience
 * the user can retry; a trade quietly executed at a five-minute-old price is a wrong
 * number in a portfolio that nothing will ever correct. Same instinct as treating
 * Finnhub's {@code {"c":0}} as a failure rather than a price.
 *
 * <h3>Money</h3>
 *
 * {@code BigDecimal} throughout and {@code double} nowhere -- binary floating point
 * cannot represent 0.1, and a portfolio that drifts is the classic result. The cash
 * movement is computed at full precision and rounded HALF_UP to 5 decimal places
 * exactly once, at the point it is written, because that is the scale of
 * {@code app_user.cash_balance}.
 */
@Service
public class TradeService {

    /** Matches {@code trade.quantity NUMERIC(18,8)}. */
    static final int QUANTITY_SCALE = 8;

    /** Matches {@code trade.price}, {@code app_user.cash_balance} and price_candle. */
    static final int MONEY_SCALE = 5;

    /** Percentages are for reading, not for arithmetic; two places is what a screen shows. */
    static final int PERCENT_SCALE = 2;

    private final TradeRepository tradeRepository;
    private final UserRepository userRepository;
    private final LivePriceService livePriceService;

    public TradeService(TradeRepository tradeRepository,
                        UserRepository userRepository,
                        LivePriceService livePriceService) {
        this.tradeRepository = tradeRepository;
        this.userRepository = userRepository;
        this.livePriceService = livePriceService;
    }

    /**
     * Place one simulated order and return both the trade that resulted and the
     * account state it produced.
     *
     * Returning the account too is not a convenience: the page would otherwise show a
     * stale balance for up to a second after a trade the user just made, until the
     * next chart poll caught up. One call, one consistent answer.
     *
     * <b>The order of the checks is deliberate.</b> The symbol is validated first, so
     * a wrong instrument is a 404 before any provider is touched. The quantity is
     * validated next, because a malformed order should not cost an upstream call
     * either. Only then is a price fetched, and only then is anything written.
     *
     * @throws com.easytrading.backend.instrument.InstrumentNotFoundException 404, not the demo instrument
     * @throws InvalidTradeException          400, the order itself is malformed
     * @throws InsufficientFundsException     409, not enough virtual cash
     * @throws InsufficientPositionException  409, selling more than held
     * @throws LivePriceUnavailableException  503, no usable price right now
     */
    @Transactional
    public TradeResult execute(User user, String rawSymbol, String rawSide, BigDecimal rawQuantity) {
        String symbol = DemoInstrument.requireSupported(rawSymbol);
        TradeSide side = parseSide(rawSide);
        BigDecimal quantity = validateQuantity(rawQuantity);

        BigDecimal price = currentPriceOrRefuse(symbol);

        // Full precision first, rounded once at the end. Rounding the multiplicands
        // instead would lose a little on every trade, always in the same direction.
        BigDecimal cash = price.multiply(quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal newBalance;
        if (side == TradeSide.BUY) {
            if (cash.compareTo(user.getCashBalance()) > 0) {
                throw new InsufficientFundsException(
                        "Not enough virtual funds for this trade. It costs " + cash
                        + " and the balance is " + user.getCashBalance() + ".");
            }
            newBalance = user.getCashBalance().subtract(cash);
        } else {
            Position held = positionFor(user.getId(), symbol);
            if (quantity.compareTo(held.quantity()) > 0) {
                throw new InsufficientPositionException(
                        "You only hold " + held.quantity().stripTrailingZeros().toPlainString()
                        + " units of " + symbol + ".");
            }
            newBalance = user.getCashBalance().add(cash);
        }

        // Both writes are inside this @Transactional method on purpose. A trade row
        // without its cash movement -- or the reverse -- is a portfolio that does not
        // add up and cannot be repaired afterwards, because nothing records what the
        // balance should have been.
        user.setCashBalance(newBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        userRepository.save(user);

        Trade saved = tradeRepository.save(new Trade(user.getId(), symbol, side, quantity, price,
                LocalDateTime.now(ZoneOffset.UTC)));

        return new TradeResult(saved, accountFor(user, symbol, price));
    }

    /**
     * The user's cash, position and unrealised P&amp;L against a price supplied by the
     * caller.
     *
     * <b>The price is a parameter rather than read here, and that is the whole
     * design.</b> {@code /api/getLiveChart} draws its candles from one snapshot and
     * then passes that same price in, so the P&amp;L on screen and the chart behind it
     * are computed from the same number at the same instant. Reading the price again
     * inside this method would re-introduce exactly the defect SCRUM-76 removed from
     * the price readout, one feature later and under a different name.
     *
     * Returns {@code null} for a user who has never traded this instrument. That is
     * not the same as a zero position: "you have no position" and "your P&amp;L is
     * 0.00" are different sentences, and a beginner reading the second would
     * reasonably think they still own something.
     */
    @Transactional(readOnly = true)
    public AccountView accountFor(User user, String rawSymbol, BigDecimal price) {
        String symbol = DemoInstrument.requireSupported(rawSymbol);
        List<Trade> trades = tradeRepository.findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(
                user.getId(), symbol);
        if (trades.isEmpty()) {
            return null;
        }

        Position position = replay(trades);
        BigDecimal quantity = position.quantity();
        BigDecimal averageCost = position.averageCost();

        if (position.isEmpty() || price == null) {
            // Traded before, holding nothing now (or no price to value it against).
            // Cash and history are still theirs; there is simply no open position, and
            // a percentage of nothing is not zero, it is undefined.
            return new AccountView(user.getCashBalance(), quantity, averageCost,
                    zero(MONEY_SCALE), zero(MONEY_SCALE), null);
        }

        BigDecimal marketValue = price.multiply(quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal costBasis = averageCost.multiply(quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal unrealisedPnl = marketValue.subtract(costBasis);

        BigDecimal unrealisedPnlPercent = averageCost.signum() == 0 ? null
                : price.subtract(averageCost)
                       .multiply(BigDecimal.valueOf(100))
                       .divide(averageCost, PERCENT_SCALE, RoundingMode.HALF_UP);

        return new AccountView(user.getCashBalance(), quantity, averageCost,
                marketValue, unrealisedPnl, unrealisedPnlPercent);
    }

    /** This user's trades for one instrument, newest first. */
    @Transactional(readOnly = true)
    public List<Trade> history(Long userId, String rawSymbol) {
        String symbol = DemoInstrument.requireSupported(rawSymbol);
        return tradeRepository.findByUserIdAndSymbolOrderByExecutedAtDescIdDesc(userId, symbol);
    }

    /** The open position, derived by replaying the trade rows. */
    @Transactional(readOnly = true)
    public Position positionFor(Long userId, String symbol) {
        return replay(tradeRepository.findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(userId, symbol));
    }

    // ---- the arithmetic, with no Spring and no database in sight ----------
    //
    // Kept as a static method over a plain List so it can be tested in milliseconds
    // with no container -- the same reason SignalService, Interval and
    // LiveCandleAggregator are shaped the way they are. This is the code most likely
    // to be quietly wrong, so it is the code that must be cheapest to test.

    static Position replay(List<Trade> trades) {
        BigDecimal quantity = zero(QUANTITY_SCALE);
        BigDecimal averageCost = zero(MONEY_SCALE);

        for (Trade trade : trades) {
            if (trade.getSide() == TradeSide.BUY) {
                BigDecimal newQuantity = quantity.add(trade.getQuantity());
                // Weighted, not the mean of the two prices: buying 0.008 at 80,000 on
                // top of 0.002 at 77,000 averages 79,400, not 78,500.
                averageCost = quantity.multiply(averageCost)
                        .add(trade.getQuantity().multiply(trade.getPrice()))
                        .divide(newQuantity, MONEY_SCALE, RoundingMode.HALF_UP);
                quantity = newQuantity;
            } else {
                quantity = quantity.subtract(trade.getQuantity());
                if (quantity.signum() <= 0) {
                    quantity = zero(QUANTITY_SCALE);
                    averageCost = zero(MONEY_SCALE);
                }
            }
        }
        return new Position(quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP), averageCost);
    }

    // ---- validation ------------------------------------------------------

    private BigDecimal currentPriceOrRefuse(String symbol) {
        LiveQuote quote = livePriceService.currentPrice(symbol);
        if (quote.outdated()) {
            throw new LivePriceUnavailableException(
                    "The live price for " + symbol + " is not current, so no trade was placed. "
                    + "Try again in a moment.");
        }
        return quote.price().price();
    }

    private static TradeSide parseSide(String rawSide) {
        if (rawSide == null) {
            throw new InvalidTradeException("'side' is required and must be BUY or SELL.");
        }
        try {
            return TradeSide.valueOf(rawSide.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidTradeException("'side' must be BUY or SELL, not '" + rawSide + "'.");
        }
    }

    private static BigDecimal validateQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new InvalidTradeException("'quantity' is required.");
        }
        if (quantity.signum() <= 0) {
            throw new InvalidTradeException("'quantity' must be greater than zero.");
        }
        // Rejected rather than rounded. Rounding would quietly buy a different amount
        // than the one the user asked for, and the difference would never be shown to
        // them -- the same class of silent alteration as a carried-forward flat candle.
        if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE) {
            throw new InvalidTradeException(
                    "'quantity' supports at most " + QUANTITY_SCALE + " decimal places.");
        }
        return quantity.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale);
    }

    /** What {@link #execute} produces: the trade, and the account it left behind. */
    public record TradeResult(Trade trade, AccountView account) {}
}
