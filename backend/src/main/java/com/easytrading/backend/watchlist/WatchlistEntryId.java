package com.easytrading.backend.watchlist;

import java.io.Serializable;
import java.util.Objects;

/** Composite key mirroring PRIMARY KEY (user_id, symbol) on watchlist (db/schema.sql). */
public class WatchlistEntryId implements Serializable {

    private Long userId;
    private String symbol;

    public WatchlistEntryId() {
        // required by JPA
    }

    public WatchlistEntryId(Long userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WatchlistEntryId other)) {
            return false;
        }
        return Objects.equals(userId, other.userId) && Objects.equals(symbol, other.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, symbol);
    }
}
