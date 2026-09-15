package com.easytrading.backend.watchlist;

import com.easytrading.backend.instrument.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WatchlistRepository extends JpaRepository<WatchlistEntry, WatchlistEntryId> {

    /**
     * One user's watchlist, as the instruments themselves, oldest saved first.
     *
     * The join is here rather than in the service because the endpoint needs
     * symbol, name and type per row and the watchlist table stores only the
     * symbol — fetching the entries and then looking up each instrument would
     * be one query per saved instrument for no reason.
     *
     * ORDER BY the join table's added_at, not by symbol: a watchlist that
     * reorders itself alphabetically when a user adds something is disorienting,
     * and without an explicit ORDER BY the row order is whatever Postgres
     * happens to return, which can differ between two identical requests.
     *
     * The WHERE filters on the user id taken from the session — never from
     * anything the caller sent. This is the only query that reads watchlist
     * rows, so that is the single place user scoping has to be right.
     */
    @Query("""
            SELECT i FROM Instrument i, WatchlistEntry w
            WHERE w.symbol = i.symbol
              AND w.userId = :userId
            ORDER BY w.addedAt
            """)
    List<Instrument> findInstrumentsForUser(@Param("userId") Long userId);
}
