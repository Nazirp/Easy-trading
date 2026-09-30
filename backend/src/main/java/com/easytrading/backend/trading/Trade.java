package com.easytrading.backend.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * One simulated trade -- a <b>position</b>, not an execution.
 *
 * It is opened {@link TradeDirection#LONG LONG} or {@link TradeDirection#SHORT SHORT}
 * at an entry price and later closed at an exit price. A short is a first-class
 * opening rather than the sale of something held, so buys and sells are never paired
 * with each other and every trade carries its own result.
 *
 * <h3>The result is a property of this row, and it is computed here</h3>
 *
 * {@link #pnl} is the one formula for open and closed trades alike -- a closed trade
 * is valued against its exit price, an open one against a live price supplied by the
 * caller. It is a plain method with no Spring, no database and no clock, so it is the
 * cheapest code in the feature to test.
 *
 * <h3>No setters</h3>
 *
 * A trade changes exactly once, when it closes, and that happens in
 * {@link TradeRepository#close} as a single guarded {@code UPDATE}. There is no
 * in-memory way to close a trade, so there is no in-memory way to close one twice.
 *
 * <h3>The prices come from the server</h3>
 *
 * {@code entryPrice} and {@code exitPrice} are read from {@code LivePriceService} at
 * the moment of opening and closing, never from a request body. They are the only
 * prices in the application persisted for their own sake: a candle can be re-fetched
 * and the live chart ages out in half an hour, but "what did I get in at, and out at?"
 * is true only at those instants.
 */
@Entity
@Table(name = "trade")
public class Trade {

    /** Matches {@code trade.quantity NUMERIC(18,8)}: crypto needs eight places. */
    static final int QUANTITY_SCALE = 8;

    /** Matches the price columns and {@code app_user.cash_balance}: NUMERIC(18,5). */
    static final int MONEY_SCALE = 5;

    /** Percentages are for reading, not for arithmetic; two places is what a screen shows. */
    static final int PERCENT_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "symbol", length = 20, nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 5, nullable = false)
    private TradeDirection direction;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;

    @Column(name = "entry_price", nullable = false, precision = 18, scale = 5)
    private BigDecimal entryPrice;

    /**
     * Set in Java rather than left to the column's {@code DEFAULT NOW()}: the new trade
     * goes straight back to the caller in the 201, and a database default is not
     * visible to the entity until the row is re-read. A {@code LocalDateTime} because
     * the column is a {@code TIMESTAMP} holding UTC; it becomes an {@code Instant} at
     * the DTO boundary, in {@code TradeController}, and nowhere else.
     */
    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    /** Null while open. Set together with {@code closedAt}, or not at all. */
    @Column(name = "exit_price", precision = 18, scale = 5)
    private BigDecimal exitPrice;

    /** Null while open. */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected Trade() {
        // required by JPA
    }

    /** A newly opened trade. There is no public way to build a closed one. */
    public Trade(Long userId, String symbol, TradeDirection direction, BigDecimal quantity,
                 BigDecimal entryPrice, LocalDateTime openedAt) {
        this.userId = userId;
        this.symbol = symbol;
        this.direction = direction;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.openedAt = openedAt;
    }

    // ---- the arithmetic --------------------------------------------------

    public boolean isOpen() {
        return closedAt == null;
    }

    /**
     * What opening this trade reserved out of the cash balance: {@code entryPrice x
     * quantity}. Leverage is 1:1, so the margin is the full notional. Rounded once, to
     * the scale of {@code cash_balance}, because this is the number that is debited.
     */
    public BigDecimal margin() {
        return entryPrice.multiply(quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Profit or loss against a reference price -- the exit price for a closed trade,
     * the live price for an open one:
     *
     * <pre>
     * pnl = max( (reference - entryPrice) x quantity x sign ,  -entryPrice x quantity )
     * </pre>
     *
     * <b>The {@code max} caps a loss at the margin.</b> A long can never lose more than
     * that anyway, because a price cannot go below zero. A short can, but only once the
     * price has doubled from entry. Capping it means closing always credits
     * {@code margin + pnl >= 0}, so the cash balance cannot go negative -- by
     * construction, not by a check someone has to remember -- and a beginner's
     * simulator never has to explain a debt.
     *
     * Computed at full precision and rounded once, HALF_UP. Rounding is monotonic, so
     * the rounded result is never below the rounded negative margin, and the credit on
     * close is never negative.
     */
    public BigDecimal pnl(BigDecimal reference) {
        BigDecimal raw = reference.subtract(entryPrice).multiply(quantity).multiply(direction.sign());
        BigDecimal floor = entryPrice.multiply(quantity).negate();
        return raw.max(floor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The same result as a return on the margin, capped at -100%. Worked from the
     * price move rather than from the rounded {@link #pnl}, so it cannot divide by a
     * margin that rounded to zero.
     */
    public BigDecimal pnlPercent(BigDecimal reference) {
        BigDecimal move = reference.subtract(entryPrice).multiply(direction.sign())
                .divide(entryPrice, 12, RoundingMode.HALF_UP);
        return move.max(BigDecimal.ONE.negate()).multiply(HUNDRED)
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    // ---- accessors -------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public TradeDirection getDirection() {
        return direction;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public BigDecimal getExitPrice() {
        return exitPrice;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }
}
