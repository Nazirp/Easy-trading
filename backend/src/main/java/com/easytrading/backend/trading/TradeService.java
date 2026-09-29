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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Opening and closing simulated trades, and what the account looks like at any moment
 * (UC04, SCRUM-83 -- the CFD model).
 *
 * <h3>A trade is a position</h3>
 *
 * {@link #open} reserves the margin -- {@code entryPrice x quantity}, leverage 1:1 --
 * out of the cash balance and writes an open trade. {@link #close} sets its exit price
 * and returns the margin plus the result. The result itself is {@link Trade#pnl}, one
 * formula for open and closed trades, with a loss capped at the margin so the cash
 * balance can never go negative.
 *
 * <h3>Prices come from here, never from the caller</h3>
 *
 * Neither method takes a price, and a {@code price} in a request body is ignored. A
 * request body is whatever the caller chooses to type, so a client-supplied price
 * means {@code {"price": 1}} buys a Bitcoin for a dollar; and a tab left open posts a
 * five-minute-old price in perfect good faith. The client says <i>what</i> and
 * <i>how much</i>, never <i>at what price</i> -- anything the server can determine,
 * the server determines. An unknown or stale price is a 503, not a guess: a refused
 * trade can be retried, a trade filled at a stale price is a wrong number nothing will
 * ever correct.
 *
 * <h3>Concurrency is handled by the database, in two statements</h3>
 *
 * Cash moves only through {@link UserRepository#adjustCash}, a relative
 * {@code UPDATE} guarded by {@code cash_balance + delta >= 0}; there is no
 * read-modify-write, so two concurrent trades cannot lose one another's change. A
 * trade closes only through {@link TradeRepository#close}, guarded by
 * {@code closed_at IS NULL}; cash is credited only after that returns 1, so a double
 * click credits once. Both run inside one {@code @Transactional} method with the rest
 * of the operation, so a trade row and its cash movement land together or not at all.
 *
 * <h3>Money</h3>
 *
 * {@code BigDecimal} throughout and {@code double} nowhere. Every price is normalised
 * to the money scale on arrival, so the margin debited on open, the credit on close
 * and the prices stored on the row are all worked from the same number.
 */
@Service
public class TradeService {

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
     * Opens a trade at the server's current price and returns it with the account it
     * left behind -- returning the account too means the page does not show a stale
     * balance for up to a second, until the next chart poll.
     *
     * <b>The order of the checks is deliberate.</b> A wrong symbol is a 404 before any
     * provider is touched; a malformed order is a 400 before a price is fetched; only
     * then is anything written, and the cash is taken before the row is inserted, so
     * an unaffordable order writes nothing at all.
     *
     * @throws com.easytrading.backend.instrument.InstrumentNotFoundException 404, not the demo instrument
     * @throws InvalidTradeException         400, the order itself is malformed
     * @throws InsufficientFundsException    409, the margin is more than the free cash
     * @throws LivePriceUnavailableException 503, no usable price right now
     */
    @Transactional
    public TradeResult open(User user, String rawSymbol, String rawDirection, BigDecimal rawQuantity) {
        String symbol = DemoInstrument.requireSupported(rawSymbol);
        TradeDirection direction = parseDirection(rawDirection);
        BigDecimal quantity = validateQuantity(rawQuantity);
        BigDecimal price = currentPriceOrRefuse(symbol);

        Trade trade = new Trade(user.getId(), symbol, direction, quantity, price, now());
        BigDecimal margin = trade.margin();
        if (margin.signum() == 0) {
            // Possible only for a vanishingly small order on a cheap instrument. A
            // trade that reserves nothing would be a free option, so it is refused.
            throw new InvalidTradeException("That order is too small: its margin rounds to zero.");
        }

        if (userRepository.adjustCash(user.getId(), margin.negate()) == 0) {
            BigDecimal free = reload(user).getCashBalance();
            throw new InsufficientFundsException("Not enough free cash for this trade. It needs "
                    + margin + " of margin and " + free + " is free.");
        }
        Trade saved = tradeRepository.save(trade);

        return new TradeResult(PricedTrade.settled(saved), accountFor(reload(user), symbol, price));
    }

    /**
     * Closes one of this user's trades, whole, at the server's current price, and
     * credits the margin plus the result.
     *
     * The guarded {@code UPDATE} runs <b>before</b> the credit. A request that loses a
     * race with another close gets 0 rows back and throws before it reaches the
     * credit, so the cash is paid out once however many times Close is clicked.
     *
     * @throws TradeNotFoundException        404, no such trade or not this user's
     * @throws TradeAlreadyClosedException   409, already closed -- including by an earlier click
     * @throws LivePriceUnavailableException 503, no usable price; the trade stays open
     */
    @Transactional
    public TradeResult close(User user, Long tradeId) {
        Trade trade = findOwn(user.getId(), tradeId);
        if (!trade.isOpen()) {
            throw alreadyClosed(tradeId);
        }
        BigDecimal price = currentPriceOrRefuse(trade.getSymbol());

        if (tradeRepository.close(tradeId, user.getId(), price, now()) == 0) {
            throw alreadyClosed(tradeId);
        }
        // Never negative: pnl is capped at minus the margin.
        userRepository.adjustCash(user.getId(), trade.margin().add(trade.pnl(price)));

        Trade closed = findOwn(user.getId(), tradeId);
        return new TradeResult(PricedTrade.settled(closed),
                accountFor(reload(user), closed.getSymbol(), price));
    }

    /**
     * The account valued against a price supplied by the caller.
     *
     * <b>The price is a parameter, and that is the whole design.</b>
     * {@code /api/getLiveChart} draws its candles from one snapshot and passes that
     * same price in, so the P&amp;L on screen and the chart behind it come from the
     * same number at the same instant. Reading the price again in here would bring
     * back exactly the defect SCRUM-76 removed from the price readout.
     *
     * One read of the user's trades, split in one pass: open ones give the margin and
     * the live result, closed ones the realised total. When open trades exist and
     * there is no price, {@code equity} and {@code unrealisedPnl} are null -- a number
     * that depends on a price nobody has is not reported as if it were known. With no
     * open trades both are exact, price or not.
     */
    @Transactional(readOnly = true)
    public AccountView accountFor(User user, String rawSymbol, BigDecimal price) {
        String symbol = DemoInstrument.requireSupported(rawSymbol);
        BigDecimal reference = price == null ? null : price.setScale(Trade.MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal margin = zero();
        BigDecimal unrealised = zero();
        BigDecimal realised = zero();
        List<PricedTrade> open = new ArrayList<>();

        for (Trade trade : tradeRepository.findByUserIdAndSymbolOrderByOpenedAtDescIdDesc(user.getId(), symbol)) {
            if (trade.isOpen()) {
                PricedTrade priced = PricedTrade.live(trade, reference);
                open.add(priced);
                margin = margin.add(trade.margin());
                if (priced.pnl() != null) {
                    unrealised = unrealised.add(priced.pnl());
                }
            } else {
                realised = realised.add(trade.pnl(trade.getExitPrice()));
            }
        }
        Collections.reverse(open);   // oldest first on the page

        boolean known = reference != null || open.isEmpty();
        BigDecimal cash = user.getCashBalance();
        return new AccountView(cash, margin,
                known ? cash.add(margin).add(unrealised) : null,
                known ? unrealised : null,
                realised,
                List.copyOf(open));
    }

    /**
     * This user's trades for one instrument, newest first, open and closed. A closed
     * trade carries its final result; an open one carries none, because this never
     * reads a live price -- open trades are valued in the account block instead.
     */
    @Transactional(readOnly = true)
    public List<PricedTrade> history(Long userId, String rawSymbol) {
        String symbol = DemoInstrument.requireSupported(rawSymbol);
        return tradeRepository.findByUserIdAndSymbolOrderByOpenedAtDescIdDesc(userId, symbol).stream()
                .map(PricedTrade::settled)
                .toList();
    }

    // ---- guards --------------------------------------------------------------

    /**
     * The server's own price, normalised to the money scale so that what is stored on
     * the row, what is debited and what is credited are the same number. A quote the
     * service marks as outdated is refused rather than traded on.
     */
    private BigDecimal currentPriceOrRefuse(String symbol) {
        LiveQuote quote = livePriceService.currentPrice(symbol);
        if (quote.outdated()) {
            throw new LivePriceUnavailableException(
                    "The live price for " + symbol + " is not current, so nothing was done. "
                    + "Try again in a moment.");
        }
        return quote.price().price().setScale(Trade.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static TradeDirection parseDirection(String rawDirection) {
        if (rawDirection == null) {
            throw new InvalidTradeException("'direction' is required and must be LONG or SHORT.");
        }
        try {
            return TradeDirection.valueOf(rawDirection.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidTradeException("'direction' must be LONG or SHORT, not '" + rawDirection + "'.");
        }
    }

    private static BigDecimal validateQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new InvalidTradeException("'quantity' is required.");
        }
        if (quantity.signum() <= 0) {
            throw new InvalidTradeException("'quantity' must be greater than zero.");
        }
        // Rejected rather than rounded: rounding would open a different size than the
        // one asked for, and the difference would never be shown.
        if (quantity.stripTrailingZeros().scale() > Trade.QUANTITY_SCALE) {
            throw new InvalidTradeException(
                    "'quantity' supports at most " + Trade.QUANTITY_SCALE + " decimal places.");
        }
        return quantity.setScale(Trade.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private Trade findOwn(Long userId, Long tradeId) {
        if (tradeId == null) {
            throw new TradeNotFoundException("No trade found.");
        }
        return tradeRepository.findByIdAndUserId(tradeId, userId)
                .orElseThrow(() -> new TradeNotFoundException("No trade found for id " + tradeId + "."));
    }

    private static TradeAlreadyClosedException alreadyClosed(Long tradeId) {
        return new TradeAlreadyClosedException("Trade " + tradeId + " is already closed.");
    }

    /**
     * The cash balance as it now stands. Needed after {@code adjustCash}, which updates
     * the row directly and leaves any {@code User} loaded earlier stale.
     */
    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(Trade.MONEY_SCALE);
    }

    /** What opening or closing produces: the trade, and the account it left behind. */
    public record TradeResult(PricedTrade trade, AccountView account) {}
}
