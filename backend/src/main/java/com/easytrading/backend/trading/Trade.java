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
import java.time.LocalDateTime;

/**
 * One executed simulated trade (UC04, SCRUM-79).
 *
 * <h3>`price` is the one price in this application that is persisted for its own sake</h3>
 *
 * Everything else that looks like a price is a cache or scenery: {@code price_candle}
 * rows can be re-fetched from Twelve Data, and the live candles are a memory window
 * that ages out in half an hour. This one cannot be recovered from anywhere. It was
 * true at one instant, no provider can be asked for it later, and a portfolio is
 * built on top of it. That is why it is copied onto the row rather than referenced:
 * a candle summarises a whole minute, and the trade happened at one moment inside it.
 *
 * <h3>Where it came from matters more than what it is</h3>
 *
 * This value is read from {@code LivePriceService} at execution time and never from
 * the request body. See {@link TradeService#execute} -- a client that sends its own
 * price is ignored, because a request body is whatever the caller chooses to type.
 *
 * <h3>Two scales on purpose</h3>
 *
 * {@code quantity} is {@code NUMERIC(18,8)} while {@code price} is {@code (18,5)}.
 * BTC trades around $76,000, so $100 of it is about 0.0013 BTC -- at five decimal
 * places a small order rounds before it is even stored. Money keeps five because
 * forex quoting needs five. Different questions, different answers.
 */
@Entity
@Table(name = "trade")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "symbol", length = 20, nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4, nullable = false)
    private TradeSide side;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;

    @Column(name = "price", nullable = false, precision = 18, scale = 5)
    private BigDecimal price;

    /**
     * Set in Java rather than left to the column's {@code DEFAULT NOW()}, unlike
     * {@code watchlist.added_at}. The difference is that a trade's timestamp goes
     * straight back to the caller in the 201 response, and a database default is
     * not visible to the entity until it is re-read -- so leaving it to Postgres
     * would mean either a null in the response or an extra round trip for a value
     * we already know. The DB default stays as the backstop for a row inserted by
     * hand.
     *
     * A {@code LocalDateTime} because that is what the {@code TIMESTAMP} column is,
     * and it holds UTC by the schema's convention. It becomes a real {@code Instant}
     * at the DTO boundary -- see {@code TradeController} -- because the API's rule is
     * that a moment carries a zone. The conversion happens in exactly one place.
     */
    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    protected Trade() {
    }

    public Trade(Long userId, String symbol, TradeSide side, BigDecimal quantity,
                 BigDecimal price, LocalDateTime executedAt) {
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = executedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public TradeSide getSide() {
        return side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }
}
