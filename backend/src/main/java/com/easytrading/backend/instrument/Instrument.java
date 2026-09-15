package com.easytrading.backend.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps 1:1 to the `instrument` table in db/schema.sql.
 *
 * A `finnhub_symbol` column and field were removed on 2026-09-15: demo trading
 * is BTC/USD only (UC04 BR6), so the single Finnhub spelling the app needs is a
 * constant next to the Finnhub client rather than a column carrying one useful
 * value across six rows. It comes back if demo trading ever covers more than
 * one instrument.
 */
@Entity
@Table(name = "instrument")
public class Instrument {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "exchange", length = 50)
    private String exchange;

    @Column(name = "type", length = 30, nullable = false)
    private InstrumentType type;

    protected Instrument() {
        // required by JPA
    }

    public Instrument(String symbol, String name, String exchange, InstrumentType type) {
        this.symbol = symbol;
        this.name = name;
        this.exchange = exchange;
        this.type = type;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public String getExchange() {
        return exchange;
    }

    public InstrumentType getType() {
        return type;
    }
}
