package com.easytrading.backend.trading;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.liveprice.DemoInstrument;
import com.easytrading.backend.liveprice.LivePriceService;
import com.easytrading.backend.liveprice.LivePriceUnavailableException;
import com.easytrading.backend.liveprice.LiveQuote;
import com.easytrading.backend.liveprice.dto.LivePrice;
import com.easytrading.backend.user.User;
import com.easytrading.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-79 — the money arithmetic and the rules around it, with no Spring, no
 * database and no Docker.
 *
 * This is the code in the feature most likely to be quietly wrong: a portfolio that
 * does not add up looks entirely plausible until someone reconciles it. So it is the
 * code that had to be cheapest to test, which is why {@link TradeService#replay} is a
 * static method over a plain list — the whole average-cost engine is exercised below
 * without mocking anything at all.
 *
 * Mockito appears only for {@code execute()}, where the collaborators are a
 * {@code JpaRepository} with thirty inherited methods and a service that opens
 * sockets. A hand-written fake is better when the interface is small and the point is
 * to count calls (see {@code LiveChartServiceTest}); it is just noise here.
 *
 * The boundaries are deliberately exact — one satoshi over affordable, a sell of
 * precisely the held quantity — because an off-by-a-rounding-unit is the defect this
 * class exists to catch, and "comfortably too much" would never have found it.
 */
class TradeServiceTest {

    private static final String SYMBOL = DemoInstrument.SYMBOL;
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private TradeRepository trades;
    private UserRepository users;
    private LivePriceService livePrices;
    private TradeService service;
    private User user;

    @BeforeEach
    void setUp() {
        trades = mock(TradeRepository.class);
        users = mock(UserRepository.class);
        livePrices = mock(LivePriceService.class);
        service = new TradeService(trades, users, livePrices);

        user = new User("nazir", "{bcrypt}hash", new BigDecimal("10000.00000"));
        setId(user, 1L);

        when(trades.findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(anyLong(), anyString()))
                .thenReturn(List.of());
        when(trades.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        priceIs("76000.00000");
    }

    // ---- the average-cost engine, no mocks --------------------------------

    @Test
    void averageCostIsWeightedByQuantityAndNotTheMeanOfThePrices() {
        // 0.002 at 77,000 then 0.008 at 80,000. The mean of the two prices is 78,500;
        // the weighted average is 79,400. Equal-sized buys cannot tell these apart,
        // which is why this test uses unequal ones.
        Position position = TradeService.replay(List.of(
                trade(TradeSide.BUY, "0.00200000", "77000.00000", 0),
                trade(TradeSide.BUY, "0.00800000", "80000.00000", 1)));

        assertThat(position.quantity()).isEqualByComparingTo("0.01000000");
        assertThat(position.averageCost()).isEqualByComparingTo("79400.00000");
    }

    @Test
    void aSellReducesTheQuantityAndLeavesTheAverageAlone() {
        Position position = TradeService.replay(List.of(
                trade(TradeSide.BUY, "0.01000000", "79400.00000", 0),
                trade(TradeSide.SELL, "0.00500000", "90000.00000", 1)));

        assertThat(position.quantity()).isEqualByComparingTo("0.00500000");
        // Selling at 90,000 does not make the remaining half cost more. Average cost
        // is what was paid, not what the market is doing.
        assertThat(position.averageCost()).isEqualByComparingTo("79400.00000");
    }

    @Test
    void sellingOutCompletelyLeavesOneStateAndNotTwo() {
        Position position = TradeService.replay(List.of(
                trade(TradeSide.BUY, "0.00500000", "79400.00000", 0),
                trade(TradeSide.SELL, "0.00500000", "91000.00000", 1)));

        assertThat(position.isEmpty()).isTrue();
        assertThat(position.quantity()).isEqualByComparingTo("0");
        // The old average is cleared rather than left behind, so "holds nothing" is
        // one condition to check and not a quantity-and-a-stale-price pair.
        assertThat(position.averageCost()).isEqualByComparingTo("0");
    }

    @Test
    void buyingAgainAfterSellingOutStartsAFreshAverage() {
        Position position = TradeService.replay(List.of(
                trade(TradeSide.BUY, "0.00500000", "79400.00000", 0),
                trade(TradeSide.SELL, "0.00500000", "91000.00000", 1),
                trade(TradeSide.BUY, "0.00200000", "50000.00000", 2)));

        assertThat(position.quantity()).isEqualByComparingTo("0.00200000");
        assertThat(position.averageCost()).isEqualByComparingTo("50000.00000");
    }

    @Test
    void noTradesAtAllIsAnEmptyPositionRatherThanAnError() {
        Position position = TradeService.replay(List.of());

        assertThat(position.isEmpty()).isTrue();
        assertThat(position.quantity()).isEqualByComparingTo("0");
    }

    // ---- cash ------------------------------------------------------------

    @Test
    void aBuyDebitsPriceTimesQuantityToFiveDecimalPlaces() {
        priceIs("76391.40000");

        service.execute(user, SYMBOL, "BUY", new BigDecimal("0.00250000"));

        // 76391.40 x 0.0025 = 190.9785 exactly.
        assertThat(user.getCashBalance()).isEqualByComparingTo("9809.02150");
    }

    @Test
    void aSellCreditsTheProceedsAtTheCurrentPriceNotAtWhatWasPaid() {
        given(trade(TradeSide.BUY, "0.00100000", "50000.00000", 0));
        priceIs("90000.00000");

        service.execute(user, SYMBOL, "SELL", new BigDecimal("0.00100000"));

        // Credited 90 at today's price, not the 50 it cost. The profit is real.
        assertThat(user.getCashBalance()).isEqualByComparingTo("10090.00000");
    }

    @Test
    void buyThenSellHigherLeavesMoreCashAndLowerLeavesLess() {
        priceIs("80000.00000");
        service.execute(user, SYMBOL, "BUY", new BigDecimal("0.01000000"));
        BigDecimal afterBuy = user.getCashBalance();
        assertThat(afterBuy).isEqualByComparingTo("9200.00000");

        given(trade(TradeSide.BUY, "0.01000000", "80000.00000", 0));
        priceIs("70000.00000");
        service.execute(user, SYMBOL, "SELL", new BigDecimal("0.01000000"));

        // Sold 10% below what it cost: 800 spent, 700 back, 100 down overall.
        assertThat(user.getCashBalance()).isEqualByComparingTo("9900.00000");
    }

    // ---- the boundaries --------------------------------------------------

    @Test
    void aBuyOneSatoshiBeyondAffordableIsRefused() {
        user.setCashBalance(new BigDecimal("76.00000"));
        priceIs("76000.00000");   // 0.001 costs exactly 76.00

        // Exactly affordable succeeds.
        service.execute(user, SYMBOL, "BUY", new BigDecimal("0.00100000"));
        assertThat(user.getCashBalance()).isEqualByComparingTo("0");

        user.setCashBalance(new BigDecimal("76.00000"));
        assertThatThrownBy(() ->
                service.execute(user, SYMBOL, "BUY", new BigDecimal("0.00100001")))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void aSellOfExactlyTheHeldQuantityWorksAndOneUnitMoreDoesNot() {
        given(trade(TradeSide.BUY, "0.00100000", "76000.00000", 0));

        assertThatThrownBy(() ->
                service.execute(user, SYMBOL, "SELL", new BigDecimal("0.00100001")))
                .isInstanceOf(InsufficientPositionException.class);

        service.execute(user, SYMBOL, "SELL", new BigDecimal("0.00100000"));
        assertThat(user.getCashBalance()).isEqualByComparingTo("10076.00000");
    }

    @Test
    void aRefusedTradeWritesNothingAtAll() {
        user.setCashBalance(new BigDecimal("1.00000"));

        assertThatThrownBy(() -> service.execute(user, SYMBOL, "BUY", new BigDecimal("1.00000000")))
                .isInstanceOf(InsufficientFundsException.class);

        // Asserting the repository was never touched, not merely that an exception
        // came back: a half-applied trade is the failure this feature cannot recover
        // from, and "it threw" does not prove nothing was written.
        verify(trades, never()).save(any(Trade.class));
        verify(users, never()).save(any(User.class));
        assertThat(user.getCashBalance()).isEqualByComparingTo("1.00000");
    }

    // ---- refusing to guess a price ---------------------------------------

    @Test
    void aStalePriceRefusesTheTradeAndWritesNothing() {
        when(livePrices.currentPrice(anyString()))
                .thenReturn(new LiveQuote(new LivePrice(SYMBOL, new BigDecimal("76000"), NOW), true));

        assertThatThrownBy(() -> service.execute(user, SYMBOL, "BUY", new BigDecimal("0.00100000")))
                .isInstanceOf(LivePriceUnavailableException.class);

        verify(trades, never()).save(any(Trade.class));
        assertThat(user.getCashBalance()).isEqualByComparingTo("10000.00000");
    }

    @Test
    void noPriceAtAllIsA503AndNotAFillAtZero() {
        when(livePrices.currentPrice(anyString()))
                .thenThrow(new LivePriceUnavailableException("nothing cached"));

        assertThatThrownBy(() -> service.execute(user, SYMBOL, "BUY", new BigDecimal("0.00100000")))
                .isInstanceOf(LivePriceUnavailableException.class);

        verify(trades, never()).save(any(Trade.class));
    }

    @Test
    void theExecutionPriceComesFromTheServerAndTheRequestCannotInfluenceIt() {
        priceIs("76543.21000");

        TradeService.TradeResult result =
                service.execute(user, SYMBOL, "BUY", new BigDecimal("0.00100000"));

        // There is no price parameter to pass, which is the actual protection; this
        // asserts the stored value is the server's and not some default or zero.
        assertThat(result.trade().getPrice()).isEqualByComparingTo("76543.21000");
    }

    // ---- malformed orders ------------------------------------------------

    @Test
    void quantityMustBePresentPositiveAndWithinEightDecimals() {
        assertThatThrownBy(() -> service.execute(user, SYMBOL, "BUY", null))
                .isInstanceOf(InvalidTradeException.class);
        assertThatThrownBy(() -> service.execute(user, SYMBOL, "BUY", BigDecimal.ZERO))
                .isInstanceOf(InvalidTradeException.class);
        assertThatThrownBy(() -> service.execute(user, SYMBOL, "BUY", new BigDecimal("-1")))
                .isInstanceOf(InvalidTradeException.class);
        // Rejected rather than rounded: rounding would buy a different amount than
        // the one asked for and never say so.
        assertThatThrownBy(() -> service.execute(user, SYMBOL, "BUY", new BigDecimal("0.000000001")))
                .isInstanceOf(InvalidTradeException.class);

        verify(trades, never()).save(any(Trade.class));
    }

    @Test
    void sideMustBeBuyOrSell() {
        assertThatThrownBy(() -> service.execute(user, SYMBOL, "HOLD", new BigDecimal("0.001")))
                .isInstanceOf(InvalidTradeException.class);
        assertThatThrownBy(() -> service.execute(user, SYMBOL, null, new BigDecimal("0.001")))
                .isInstanceOf(InvalidTradeException.class);

        // Case and whitespace are the caller's problem to get wrong, not the user's.
        service.execute(user, SYMBOL, " buy ", new BigDecimal("0.00100000"));
    }

    @Test
    void anythingButTheDemoInstrumentIsA404BeforeAnyProviderIsTouched() {
        assertThatThrownBy(() -> service.execute(user, "EUR/USD", "BUY", new BigDecimal("0.001")))
                .isInstanceOf(InstrumentNotFoundException.class);

        verify(livePrices, never()).currentPrice(anyString());
        verify(trades, never()).save(any(Trade.class));
    }

    // ---- the account block -----------------------------------------------

    @Test
    void aUserWhoHasNeverTradedHasNoAccountBlockAtAll() {
        assertThat(service.accountFor(user, SYMBOL, new BigDecimal("76000"))).isNull();
    }

    @Test
    void unrealisedProfitAndLossIsValuedAtThePricePassedIn() {
        given(trade(TradeSide.BUY, "0.01000000", "70000.00000", 0));

        AccountView account = service.accountFor(user, SYMBOL, new BigDecimal("77000.00000"));

        assertThat(account.quantity()).isEqualByComparingTo("0.01000000");
        assertThat(account.averageCost()).isEqualByComparingTo("70000.00000");
        assertThat(account.marketValue()).isEqualByComparingTo("770.00000");
        assertThat(account.unrealisedPnl()).isEqualByComparingTo("70.00000");
        assertThat(account.unrealisedPnlPercent()).isEqualByComparingTo("10.00");
    }

    @Test
    void aLosingPositionReportsNegativeProfitAndLoss() {
        given(trade(TradeSide.BUY, "0.01000000", "80000.00000", 0));

        AccountView account = service.accountFor(user, SYMBOL, new BigDecimal("76000.00000"));

        assertThat(account.unrealisedPnl()).isEqualByComparingTo("-40.00000");
        assertThat(account.unrealisedPnlPercent()).isEqualByComparingTo("-5.00");
    }

    @Test
    void someoneWhoTradedAndSoldOutKeepsTheirBlockButHasNoPercentage() {
        given(trade(TradeSide.BUY, "0.00100000", "76000.00000", 0),
              trade(TradeSide.SELL, "0.00100000", "80000.00000", 1));

        AccountView account = service.accountFor(user, SYMBOL, new BigDecimal("76000.00000"));

        // Not null — their cash and their history are still theirs.
        assertThat(account).isNotNull();
        assertThat(account.quantity()).isEqualByComparingTo("0");
        assertThat(account.unrealisedPnl()).isEqualByComparingTo("0");
        // A percentage of no position is undefined, not zero. Rendering "0.00%" would
        // claim a break-even that does not exist.
        assertThat(account.unrealisedPnlPercent()).isNull();
    }

    @Test
    void theAccountReturnedWithATradeAlreadyReflectsIt() {
        priceIs("76000.00000");

        TradeService.TradeResult result =
                service.execute(user, SYMBOL, "BUY", new BigDecimal("0.00100000"));

        // The page must not show a stale balance for up to a second after a trade the
        // user just placed, so the 201 carries the account it produced.
        assertThat(result.account()).isNotNull();
        assertThat(result.account().cash()).isEqualByComparingTo("9924.00000");
    }

    // ---- helpers ---------------------------------------------------------

    private void priceIs(String price) {
        when(livePrices.currentPrice(anyString()))
                .thenReturn(new LiveQuote(new LivePrice(SYMBOL, new BigDecimal(price), NOW), false));
    }

    /** Pretend these rows are already in the database for this user and symbol. */
    private void given(Trade... existing) {
        when(trades.findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(anyLong(), anyString()))
                .thenReturn(new ArrayList<>(List.of(existing)));
    }

    private static Trade trade(TradeSide side, String quantity, String price, int minutesAfterBase) {
        return new Trade(1L, SYMBOL, side, new BigDecimal(quantity), new BigDecimal(price),
                LocalDateTime.of(2026, 9, 21, 9, 0).plusMinutes(minutesAfterBase));
    }

    /** User's id is database-generated; the tests need one without a database. */
    private static void setId(User user, Long id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
