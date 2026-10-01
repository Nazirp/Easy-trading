package com.easytrading.backend.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps 1:1 to the `instrument` table in db/schema.sql.
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

    public InstrumentType getType() {
        return type;
    }
}
