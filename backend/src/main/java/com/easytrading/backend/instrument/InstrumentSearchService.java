package com.easytrading.backend.instrument;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Business logic layer: only the local DB is searched, no call out to
 * Twelve Data. Two outcomes for the frontend: a non-empty collection of
 * matches, or an InstrumentNotFoundException ("not found" signal) --
 * never a silent empty-but-200 response.
 */
@Service
public class InstrumentSearchService {

    private static final int MIN_QUERY_LENGTH = 1;

    private final InstrumentRepository repository;

    public InstrumentSearchService(InstrumentRepository repository) {
        this.repository = repository;
    }

    public List<Instrument> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < MIN_QUERY_LENGTH) {
            throw new InvalidSearchQueryException("Please enter an instrument symbol or name.");
        }

        List<Instrument> found = repository.searchLocal(query);
        if (found.isEmpty()) {
            throw new InstrumentNotFoundException("No instrument found for '" + query + "'.");
        }
        return found;
    }
}
