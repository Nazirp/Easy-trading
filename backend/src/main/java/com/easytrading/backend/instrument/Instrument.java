package com.easytrading.backend.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps 1:1 to the `instrument` table in db/schema.sql. */
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

    /**
     * Finnhub's symbol for this instrument — differs from `symbol` for
     * forex/crypto ('OANDA:EUR_USD', 'BINANCE:BTCUSDT'), usually identical for
     * stocks. Unused in MS3; needed by demo trading's live feed in MS4
     * (SCRUM-23 / SCRUM-25). Mapped now so the entity matches the schema and
     * ddl-auto: validate passes.
     */
    @Column(name = "finnhub_symbol", length = 20)
    private String finnhubSymbol;

    protected Instrument() {
        // required by JPA
    }

    public Instrument(String symbol, String name, String exchange, InstrumentType type) {
        this(symbol, name, exchange, type, null);
    }

    public Instrument(String symbol, String name, String exchange, InstrumentType type, String finnhubSymbol) {
        this.symbol = symbol;
        this.name = name;
        this.exchange = exchange;
        this.type = type;
        this.finnhubSymbol = finnhubSymbol;
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

    public String getFinnhubSymbol() {
        return finnhubSymbol;
    }
}
