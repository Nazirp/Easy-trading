package com.easytrading.backend.instrument;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InstrumentRepository extends JpaRepository<Instrument, String> {

    /**
     * Local cache lookup -- case-insensitive substring match
     * on symbol OR name, with exact symbol matches ranked first.
     */
    @Query("""
            SELECT i FROM Instrument i
            WHERE LOWER(i.symbol) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(i.name)   LIKE LOWER(CONCAT('%', :query, '%'))
            ORDER BY CASE WHEN LOWER(i.symbol) = LOWER(:query) THEN 0 ELSE 1 END, i.name
            """)
    List<Instrument> searchLocal(@Param("query") String query);
}
