package com.easytrading.backend.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstrumentRepository extends JpaRepository<Instrument, String> {

    /**
     * Local cache lookup for UC01 step 4 -- case-insensitive match on
     * symbol (exact) or name (substring), exact symbol matches ranked
     * first.
     *
     * Deliberately a separate implementation from search_instrument() in
     * db/schema.sql: that one is a DB-side manual verification helper
     * (proves the DB itself is queryable, independent of the app), this is
     * the app's real query path via Spring Data. Same idea, two
     * independent implementations -- if their behavior ever needs to be
     * identical, that's a decision to make explicitly, not an accident to
     * assume away.
     */
    @Query("""
            SELECT i FROM Instrument i
            WHERE LOWER(i.symbol) = LOWER(:query)
               OR LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))
            ORDER BY CASE WHEN LOWER(i.symbol) = LOWER(:query) THEN 0 ELSE 1 END, i.name
            """)
    List<Instrument> searchLocal(@Param("query") String query);
}
