package com.easytrading.backend.trading;

import java.math.BigDecimal;

/**
 * A trade together with its result against some reference price -- or with no result,
 * when there is no price to measure it against.
 *
 * One type for both places a trade is shown with a number beside it: the history,
 * where a closed trade is measured against its exit price, and the account block,
 * where an open trade is measured against the chart's live price. Having the two
 * build the same record is what keeps "a trade and its P&amp;L" one shape on the wire.
 *
 * {@code pnl} and {@code pnlPercent} are null when there is no reference -- an open
 * trade in the history, or any open trade while the live price is unavailable. Null is
 * not zero: a zero would claim a break-even that nobody measured.
 */
public record PricedTrade(Trade trade, BigDecimal pnl, BigDecimal pnlPercent) {

    /**
     * A closed trade with its final result; an open one with none. Makes no reference
     * to the market -- which is why {@code GET /api/trades} never reads a live price.
     */
    static PricedTrade settled(Trade trade) {
        return trade.isOpen() ? unpriced(trade) : at(trade, trade.getExitPrice());
    }

    /** An open trade valued against the live price, or unpriced when there is none. */
    static PricedTrade live(Trade trade, BigDecimal price) {
        return price == null ? unpriced(trade) : at(trade, price);
    }

    private static PricedTrade at(Trade trade, BigDecimal reference) {
        return new PricedTrade(trade, trade.pnl(reference), trade.pnlPercent(reference));
    }

    private static PricedTrade unpriced(Trade trade) {
        return new PricedTrade(trade, null, null);
    }
}
