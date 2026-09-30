package com.easytrading.backend.watchlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Maps 1:1 to the `watchlist` table in db/schema.sql.
 *
 * One row = one instrument saved by one user. The primary key is the pair
 * (user_id, symbol), which is what makes no instrument twice on one
 * watchlist a property of the database rather than something the service has
 * to remember to check.
 *
 * Deliberately plain `userId` and `symbol` columns rather than @ManyToOne
 * associations to User and Instrument. The foreign keys are real and enforced
 * by the database; mapping them as object references here would buy lazy
 * loading nobody needs and would let a caller walk from a watchlist row to a
 * whole User entity, password hash included. The join that the list endpoint
 * needs is written explicitly in WatchlistRepository instead.
 */
@Entity
@Table(name = "watchlist")
@IdClass(WatchlistEntryId.class)
public class WatchlistEntry {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    /**
     * Written by the database's own DEFAULT (NOW() AT TIME ZONE 'UTC'), same as
     * app_user.created_at — one clock for every row whatever the server's
     * timezone. Null on a freshly saved instance until the row is read back;
     * nothing needs it in a response, only the ORDER BY does.
     */
    @Column(name = "added_at", insertable = false, updatable = false)
    private LocalDateTime addedAt;

    protected WatchlistEntry() {
        // required by JPA
    }

    public WatchlistEntry(Long userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
    }
}
