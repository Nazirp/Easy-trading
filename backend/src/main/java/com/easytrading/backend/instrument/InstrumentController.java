package com.easytrading.backend.instrument;

import com.easytrading.backend.instrument.dto.InstrumentMatchResponse;
import com.easytrading.backend.instrument.dto.SearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/search?q=... — see backend/CONTRACTS.md.
 *
 * Path and query param are the FINAL agreed shape; the frontend builds against
 * this now and it will not be renamed later. The /api prefix separates JSON
 * endpoints from the HTML pages this same application serves (see WebConfig),
 * so a page route can never collide with an API route.
 *
 * DB-only (scope decision, 2026-08-23): a match returns a collection, no match
 * throws InstrumentNotFoundException -> 404 NOT_FOUND, empty query throws
 * InvalidSearchQueryException -> 400 INVALID_QUERY. Both handled centrally in
 * com.easytrading.backend.common.ApiExceptionHandler.
 */
@RestController
public class InstrumentController {

    private final InstrumentSearchService searchService;

    public InstrumentController(InstrumentSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam(value = "q", required = false) String query) {
        var results = searchService.search(query).stream()
                .map(i -> new InstrumentMatchResponse(i.getSymbol(), i.getName(), i.getType().name().toLowerCase()))
                .toList();
        return new SearchResponse(results);
    }
}
