package com.easytrading.backend.price;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** Composite key mirroring PRIMARY KEY (symbol, interval, datetime) on price_candle (db/schema.sql). */
public class PriceId implements Serializable {

    private String symbol;
    private String interval;
    private LocalDateTime datetime;

    public PriceId() {
        // required by JPA
    }

    public PriceId(String symbol, String interval, LocalDateTime datetime) {
        this.symbol = symbol;
        this.interval = interval;
        this.datetime = datetime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PriceId other)) {
            return false;
        }
        return Objects.equals(symbol, other.symbol)
                && Objects.equals(interval, other.interval)
                && Objects.equals(datetime, other.datetime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, interval, datetime);
    }
}
