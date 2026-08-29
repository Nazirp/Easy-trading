package com.easytrading.backend.price;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps 1:1 to the `price_candle` table in db/schema.sql. All three intervals
 * live in this one table, distinguished by the `interval` column, which is part
 * of the primary key.
 */
@Entity
@Table(name = "price_candle")
@IdClass(PriceId.class)
public class Price {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    // `interval` is a SQL keyword. The DDL creates it unquoted (so the actual
    // column is lowercase `interval`); quoting it here makes Hibernate emit it
    // as an explicit identifier rather than risk it being parsed as the type
    // keyword. If Hibernate ever complains about the quoting, the plain
    // name = "interval" form is the fallback.
    @Id
    @Column(name = "\"interval\"", length = 10)
    private String interval;

    @Id
    @Column(name = "datetime")
    private LocalDateTime datetime;

    @Column(nullable = false)
    private BigDecimal open;

    @Column(nullable = false)
    private BigDecimal high;

    @Column(nullable = false)
    private BigDecimal low;

    @Column(nullable = false)
    private BigDecimal close;

    private Long volume;

    protected Price() {
        // required by JPA
    }

    public Price(String symbol, String interval, LocalDateTime datetime, BigDecimal open, BigDecimal high,
                 BigDecimal low, BigDecimal close, Long volume) {
        this.symbol = symbol;
        this.interval = interval;
        this.datetime = datetime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getInterval() {
        return interval;
    }

    public LocalDateTime getDatetime() {
        return datetime;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public Long getVolume() {
        return volume;
    }
}
