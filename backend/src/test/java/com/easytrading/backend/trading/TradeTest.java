package com.easytrading.backend.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one formula, with nothing around it: no Spring, no database, no
 * mocks, no clock.
 *
 * {@link Trade#pnl} decides every number the account shows — the live P&amp;L of an
 * open trade, the result of a closed one, and the cash a close credits back. It is the
 * code most likely to be quietly wrong and the cheapest to test, so the edges are
 * tested exactly: at entry, the moment the cap engages, and past it.
 */
class TradeTest {

    private static Trade trade(TradeDirection direction, String quantity, String entryPrice) {
        return new Trade(1L, "BTC/USD", direction, new BigDecimal(quantity), new BigDecimal(entryPrice),
                LocalDateTime.of(2026, 9, 29, 10, 0));
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    // ---- direction ----------------------------------------------------------

    @Test
    void aLongProfitsWhenThePriceRises() {
        Trade longTrade = trade(TradeDirection.LONG, "0.00250000", "76000.00000");

        assertThat(longTrade.pnl(price("78000"))).isEqualByComparingTo("5.00000");
        assertThat(longTrade.pnl(price("74000"))).isEqualByComparingTo("-5.00000");
    }

    @Test
    void aShortProfitsWhenThePriceFalls() {
        Trade shortTrade = trade(TradeDirection.SHORT, "0.00250000", "76000.00000");

        // The same two moves as above, with the opposite sign.
        assertThat(shortTrade.pnl(price("74000"))).isEqualByComparingTo("5.00000");
        assertThat(shortTrade.pnl(price("78000"))).isEqualByComparingTo("-5.00000");
    }

    @Test
    void atTheEntryPriceTheResultIsExactlyZero() {
        assertThat(trade(TradeDirection.LONG, "0.5", "76391.4").pnl(price("76391.4"))).isEqualByComparingTo("0");
        assertThat(trade(TradeDirection.SHORT, "0.5", "76391.4").pnl(price("76391.4"))).isEqualByComparingTo("0");
    }

    // ---- the cap --------------------------------------------------------------

    @Test
    void aShortAtExactlyTwiceItsEntryHasLostExactlyItsMargin() {
        Trade shortTrade = trade(TradeDirection.SHORT, "0.001", "76000");

        // The point where the cap engages: the raw loss and the margin are equal.
        assertThat(shortTrade.pnl(price("152000"))).isEqualByComparingTo("-76.00000");
        assertThat(shortTrade.pnlPercent(price("152000"))).isEqualByComparingTo("-100.00");
    }

    @Test
    void aShortPastTwiceItsEntryIsCappedAtItsMargin() {
        Trade shortTrade = trade(TradeDirection.SHORT, "0.001", "76000");

        // Raw loss would be -124.00. Capped, so closing credits margin + pnl = 0 and the
        // cash balance cannot go negative — by construction, not by a check.
        BigDecimal pnl = shortTrade.pnl(price("200000"));
        assertThat(pnl).isEqualByComparingTo("-76.00000");
        assertThat(shortTrade.margin().add(pnl)).isEqualByComparingTo("0");
        assertThat(shortTrade.pnlPercent(price("200000"))).isEqualByComparingTo("-100.00");
    }

    @Test
    void aLongCanLoseItsWholeMarginButNeverMore() {
        Trade longTrade = trade(TradeDirection.LONG, "0.001", "76000");

        // A price of zero is the worst a long can do, and it lands exactly on the cap —
        // which is why the cap only ever bites on shorts.
        assertThat(longTrade.pnl(price("0"))).isEqualByComparingTo("-76.00000");
        assertThat(longTrade.pnlPercent(price("0"))).isEqualByComparingTo("-100.00");
    }

    @Test
    void theCreditOnCloseIsNeverNegativeAtAnyPrice() {
        Trade shortTrade = trade(TradeDirection.SHORT, "0.00131579", "76391.40000");
        for (String p : new String[] {"1", "76391.4", "152782.8", "152782.80001", "1000000"}) {
            assertThat(shortTrade.margin().add(shortTrade.pnl(price(p))).signum() >= 0)
                    .as("credit at %s", p).isTrue();
        }
    }

    // ---- margin, scale and percent --------------------------------------------

    @Test
    void theMarginIsTheFullNotionalRoundedToTheMoneyScale() {
        // Leverage is 1:1. 0.00131579 x 76391.40 = 100.51504... -> 100.51504 at 5 places.
        assertThat(trade(TradeDirection.LONG, "0.00131579", "76391.40000").margin())
                .isEqualByComparingTo("100.51504");
    }

    @Test
    void theResultIsRoundedOnceToFivePlaces() {
        BigDecimal pnl = trade(TradeDirection.LONG, "0.00131579", "76391.40000").pnl(price("76391.41"));
        assertThat(pnl.scale()).isEqualTo(5);
    }

    @Test
    void thePercentageIsTheReturnOnTheMargin() {
        // +2.63% for a long when the price moves 76,000 -> 78,000, whatever the size.
        assertThat(trade(TradeDirection.LONG, "0.001", "76000").pnlPercent(price("78000")))
                .isEqualByComparingTo("2.63");
        assertThat(trade(TradeDirection.LONG, "7", "76000").pnlPercent(price("78000")))
                .isEqualByComparingTo("2.63");
        assertThat(trade(TradeDirection.SHORT, "0.001", "76000").pnlPercent(price("78000")))
                .isEqualByComparingTo("-2.63");
    }

    @Test
    void aNewTradeIsOpen() {
        Trade trade = trade(TradeDirection.LONG, "1", "100");
        assertThat(trade.isOpen()).isTrue();
        assertThat(trade.getExitPrice()).isNull();
        assertThat(trade.getClosedAt()).isNull();
    }
}
