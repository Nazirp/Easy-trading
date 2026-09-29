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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-83 — opening, closing and valuing trades, with no Spring, no database and no
 * Docker. The arithmetic itself is {@code TradeTest}; this is the rules around it.
 *
 * Several tests assert what was <i>not</i> called rather than only what was thrown:
 * "it threw" does not prove that nothing was written or credited, and a trade row
 * without its cash movement — or a close credited twice — is exactly the kind of
 * wrong number that looks plausible until somebody reconciles the account.
 */
class TradeServiceTest {

    private static final String SYMBOL = DemoInstrument.SYMBOL;
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final Long USER_ID = 1L;

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

        user = userWithCash("10000.00000");
        when(users.findById(USER_ID)).thenAnswer(inv -> Optional.of(user));
        when(users.adjustCash(eq(USER_ID), any(BigDecimal.class))).thenReturn(1);
        when(trades.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trades.close(anyLong(), anyLong(), any(BigDecimal.class), any(LocalDateTime.class))).thenReturn(1);
        given();
        priceIs("76000.00000");
    }

    // ---- opening ------------------------------------------------------------------

    @Test
    void openingReservesTheMarginAndWritesAnOpenTrade() {
        var result = service.open(user, SYMBOL, "LONG", new BigDecimal("0.0025"));

        // 0.0025 x 76,000 = 190.00 reserved out of cash, as one relative update.
        verify(users).adjustCash(USER_ID, new BigDecimal("-190.00000"));
        verify(trades).save(any(Trade.class));

        Trade opened = result.trade().trade();
        assertThat(opened.getDirection()).isEqualTo(TradeDirection.LONG);
        assertThat(opened.getEntryPrice()).isEqualByComparingTo("76000");
        assertThat(opened.isOpen()).isTrue();
        // An open trade has no result outside the account block.
        assertThat(result.trade().pnl()).isNull();
    }

    @Test
    void aShortOpensWithoutHoldingAnything() {
        // The spot model refused this with INSUFFICIENT_POSITION. Under CFD a short is a
        // first-class opening, limited only by free cash like a long.
        var result = service.open(user, SYMBOL, "short", new BigDecimal("0.0025"));

        assertThat(result.trade().trade().getDirection()).isEqualTo(TradeDirection.SHORT);
        verify(users).adjustCash(USER_ID, new BigDecimal("-190.00000"));
    }

    @Test
    void anUnaffordableTradeIsA409AndWritesNothing() {
        // The guarded update matched no row: the margin is more than the free cash.
        when(users.adjustCash(eq(USER_ID), any(BigDecimal.class))).thenReturn(0);

        assertThatThrownBy(() -> service.open(user, SYMBOL, "LONG", new BigDecimal("1")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("76000.00000");

        // The cash is taken BEFORE the row is written, so a refusal leaves no trade.
        verify(trades, never()).save(any(Trade.class));
    }

    @Test
    void noUsablePriceIsA503AndTouchesNothing() {
        when(livePrices.currentPrice(anyString()))
                .thenReturn(new LiveQuote(new LivePrice(SYMBOL, new BigDecimal("76000"), NOW), true));

        assertThatThrownBy(() -> service.open(user, SYMBOL, "LONG", new BigDecimal("0.001")))
                .isInstanceOf(LivePriceUnavailableException.class);

        verify(users, never()).adjustCash(anyLong(), any(BigDecimal.class));
        verify(trades, never()).save(any(Trade.class));
    }

    @Test
    void theClientNeverChoosesThePrice() {
        priceIs("81234.56789");

        var result = service.open(user, SYMBOL, "LONG", new BigDecimal("1"));

        // The server's own price, normalised to the money scale once, so the stored
        // entry price and the margin debited are worked from the same number.
        assertThat(result.trade().trade().getEntryPrice()).isEqualByComparingTo("81234.56789");
        verify(users).adjustCash(USER_ID, new BigDecimal("-81234.56789"));
    }

    @Test
    void malformedOrdersAre400sBeforeAnyPriceIsFetched() {
        assertThatThrownBy(() -> service.open(user, SYMBOL, "BUY", BigDecimal.ONE))
                .isInstanceOf(InvalidTradeException.class).hasMessageContaining("LONG or SHORT");
        assertThatThrownBy(() -> service.open(user, SYMBOL, null, BigDecimal.ONE))
                .isInstanceOf(InvalidTradeException.class);
        assertThatThrownBy(() -> service.open(user, SYMBOL, "LONG", null))
                .isInstanceOf(InvalidTradeException.class);
        assertThatThrownBy(() -> service.open(user, SYMBOL, "LONG", BigDecimal.ZERO))
                .isInstanceOf(InvalidTradeException.class);
        assertThatThrownBy(() -> service.open(user, SYMBOL, "LONG", new BigDecimal("-1")))
                .isInstanceOf(InvalidTradeException.class);
        assertThatThrownBy(() -> service.open(user, SYMBOL, "LONG", new BigDecimal("0.123456789")))
                .isInstanceOf(InvalidTradeException.class).hasMessageContaining("8 decimal places");

        verify(livePrices, never()).currentPrice(anyString());
    }

    @Test
    void anyInstrumentButTheDemoOneIsA404BeforeAProviderIsTouched() {
        assertThatThrownBy(() -> service.open(user, "EUR/USD", "LONG", BigDecimal.ONE))
                .isInstanceOf(InstrumentNotFoundException.class);
        verify(livePrices, never()).currentPrice(anyString());
    }

    // ---- closing ------------------------------------------------------------------

    @Test
    void closingCreditsTheMarginPlusTheResult() {
        Trade open = openTrade(TradeDirection.LONG, "0.0025", "76000");
        when(trades.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(open));
        priceIs("78000");

        service.close(user, 7L);

        // Margin 190.00 comes back, plus (78,000 - 76,000) x 0.0025 = 5.00.
        verify(trades).close(eq(7L), eq(USER_ID), eq(new BigDecimal("78000.00000")), any(LocalDateTime.class));
        verify(users).adjustCash(USER_ID, new BigDecimal("195.00000"));
    }

    @Test
    void aLosingShortCreditsTheMarginMinusTheLoss() {
        Trade open = openTrade(TradeDirection.SHORT, "0.0025", "76000");
        when(trades.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(open));
        priceIs("78000");

        service.close(user, 7L);

        verify(users).adjustCash(USER_ID, new BigDecimal("185.00000"));
    }

    @Test
    void theLosingSideOfADoubleClickIsA409AndCreditsNothing() {
        // Both requests saw the trade open; the guarded UPDATE lets exactly one through.
        Trade open = openTrade(TradeDirection.LONG, "1", "76000");
        when(trades.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(open));
        when(trades.close(anyLong(), anyLong(), any(BigDecimal.class), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> service.close(user, 7L))
                .isInstanceOf(TradeAlreadyClosedException.class);

        verify(users, never()).adjustCash(anyLong(), any(BigDecimal.class));
    }

    @Test
    void closingAClosedTradeIsA409BeforeAnyPriceIsFetched() {
        Trade closed = closedTrade(TradeDirection.LONG, "1", "76000", "77000");
        when(trades.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.close(user, 7L))
                .isInstanceOf(TradeAlreadyClosedException.class);

        verify(livePrices, never()).currentPrice(anyString());
        verify(users, never()).adjustCash(anyLong(), any(BigDecimal.class));
    }

    @Test
    void someoneElsesTradeIsNotFoundAndNothingHappens() {
        // Asked for (id, MY id) and nothing came back — the trade exists, but not for me.
        when(trades.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(user, 7L))
                .isInstanceOf(TradeNotFoundException.class);

        verify(trades, never()).findById(any());
        verify(trades, never()).close(anyLong(), anyLong(), any(BigDecimal.class), any(LocalDateTime.class));
    }

    @Test
    void noUsablePriceLeavesTheTradeOpen() {
        Trade open = openTrade(TradeDirection.LONG, "1", "76000");
        when(trades.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(open));
        when(livePrices.currentPrice(anyString()))
                .thenReturn(new LiveQuote(new LivePrice(SYMBOL, new BigDecimal("76000"), NOW), true));

        assertThatThrownBy(() -> service.close(user, 7L))
                .isInstanceOf(LivePriceUnavailableException.class);

        verify(trades, never()).close(anyLong(), anyLong(), any(BigDecimal.class), any(LocalDateTime.class));
        verify(users, never()).adjustCash(anyLong(), any(BigDecimal.class));
    }

    // ---- the account ---------------------------------------------------------------

    @Test
    void someoneWhoHasNeverTradedHasAFullAccountAndNoOpenTrades() {
        AccountView account = service.accountFor(user, SYMBOL, new BigDecimal("76000"));

        // Never null any more: cash is always a fact worth showing.
        assertThat(account.cash()).isEqualByComparingTo("10000");
        assertThat(account.margin()).isEqualByComparingTo("0");
        assertThat(account.equity()).isEqualByComparingTo("10000");
        assertThat(account.unrealisedPnl()).isEqualByComparingTo("0");
        assertThat(account.realisedPnl()).isEqualByComparingTo("0");
        assertThat(account.openTrades()).isEmpty();
    }

    @Test
    void equityIsCashPlusMarginPlusTheLiveResult() {
        user = userWithCash("9620.00000");   // after two opens of 190.00 each
        given(openTrade(TradeDirection.LONG, "0.0025", "76000"),
              openTrade(TradeDirection.SHORT, "0.0025", "76000"),
              closedTrade(TradeDirection.LONG, "0.001", "70000", "72000"));

        AccountView account = service.accountFor(user, SYMBOL, new BigDecimal("78000"));

        assertThat(account.margin()).isEqualByComparingTo("380.00000");
        // Long +5.00, short -5.00: the live results cancel.
        assertThat(account.unrealisedPnl()).isEqualByComparingTo("0");
        assertThat(account.equity()).isEqualByComparingTo("10000.00000");
        // The closed trade made (72,000 - 70,000) x 0.001 = 2.00, and it is only here.
        assertThat(account.realisedPnl()).isEqualByComparingTo("2.00000");
        assertThat(account.openTrades()).hasSize(2);
    }

    @Test
    void openTradesComeBackOldestFirstWithTheirLiveResult() {
        Trade older = openTrade(TradeDirection.LONG, "1", "76000", 0);
        Trade newer = openTrade(TradeDirection.SHORT, "1", "77000", 5);
        given(newer, older);   // the repository answers newest first

        AccountView account = service.accountFor(user, SYMBOL, new BigDecimal("76500"));

        assertThat(account.openTrades().get(0).trade()).isSameAs(older);
        assertThat(account.openTrades().get(0).pnl()).isEqualByComparingTo("500.00000");
        assertThat(account.openTrades().get(1).pnl()).isEqualByComparingTo("500.00000");
    }

    @Test
    void withNoPriceTheNumbersThatNeedOneAreNullNotGuessed() {
        given(openTrade(TradeDirection.LONG, "1", "76000"));

        AccountView account = service.accountFor(user, SYMBOL, null);

        assertThat(account.equity()).isNull();
        assertThat(account.unrealisedPnl()).isNull();
        assertThat(account.openTrades().get(0).pnl()).isNull();
        // What does not depend on the price is still exact.
        assertThat(account.cash()).isEqualByComparingTo("10000");
        assertThat(account.margin()).isEqualByComparingTo("76000.00000");
    }

    @Test
    void withNoPriceAndNothingOpenTheEquityIsStillKnown() {
        AccountView account = service.accountFor(user, SYMBOL, null);

        assertThat(account.equity()).isEqualByComparingTo("10000");
        assertThat(account.unrealisedPnl()).isEqualByComparingTo("0");
    }

    // ---- history --------------------------------------------------------------------

    @Test
    void historyCarriesTheResultOfClosedTradesAndNothingForOpenOnes() {
        given(openTrade(TradeDirection.LONG, "1", "76000"),
              closedTrade(TradeDirection.SHORT, "1", "76000", "75000"));

        List<PricedTrade> history = service.history(USER_ID, SYMBOL);

        assertThat(history.get(0).pnl()).isNull();   // open: this never reads a live price
        assertThat(history.get(1).pnl()).isEqualByComparingTo("1000.00000");
        verify(livePrices, never()).currentPrice(anyString());
    }

    // ---- helpers ----------------------------------------------------------------------

    private void priceIs(String price) {
        when(livePrices.currentPrice(anyString()))
                .thenReturn(new LiveQuote(new LivePrice(SYMBOL, new BigDecimal(price), NOW), false));
    }

    /** Pretend these rows are this user's trades, in the order the repository returns them. */
    private void given(Trade... rows) {
        when(trades.findByUserIdAndSymbolOrderByOpenedAtDescIdDesc(anyLong(), anyString()))
                .thenReturn(new ArrayList<>(List.of(rows)));
    }

    private static Trade openTrade(TradeDirection direction, String quantity, String entry) {
        return openTrade(direction, quantity, entry, 0);
    }

    private static Trade openTrade(TradeDirection direction, String quantity, String entry, int minute) {
        return new Trade(USER_ID, SYMBOL, direction, new BigDecimal(quantity), new BigDecimal(entry),
                LocalDateTime.of(2026, 9, 29, 9, 0).plusMinutes(minute));
    }

    /**
     * A closed trade, as the database would hand one back. Production code has no way to
     * build one — a trade closes only through the guarded UPDATE — so the test sets the
     * two fields the way Hibernate does, by reflection.
     */
    private static Trade closedTrade(TradeDirection direction, String quantity, String entry, String exit) {
        Trade trade = openTrade(direction, quantity, entry);
        set(trade, "exitPrice", new BigDecimal(exit));
        set(trade, "closedAt", LocalDateTime.of(2026, 9, 29, 9, 30));
        return trade;
    }

    private User userWithCash(String cash) {
        User u = new User("nazir", "{bcrypt}hash", new BigDecimal(cash));
        set(u, "id", USER_ID);
        return u;
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
