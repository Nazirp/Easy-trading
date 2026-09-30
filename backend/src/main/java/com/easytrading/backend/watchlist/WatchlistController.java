package com.easytrading.backend.watchlist;

import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.dto.InstrumentMatchResponse;
import com.easytrading.backend.user.SessionUser;
import com.easytrading.backend.user.User;
import com.easytrading.backend.watchlist.dto.WatchlistRequest;
import com.easytrading.backend.watchlist.dto.WatchlistResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET / POST / DELETE /api/watchlist — see backend/CONTRACTS.md.
 *
 * Every method begins by asking
 * SessionUser for the current account, which answers with a 401 rather than a
 * 500 or somebody else's data when nobody is logged in. The user id is then the
 * first argument to every service call — a caller has no way to name a
 * different user, because no parameter of any of these endpoints is a user.
 *
 * DELETE takes the symbol as a QUERY PARAMETER, not a path segment. Symbols
 * contain slashes ("BTC/USD"), so /api/watchlist/BTC/USD does not route, and
 * an encoded %2F in a path is rejected by Tomcat by default. This looks less
 * tidy than a path variable and is not an oversight.
 *
 * Errors are mapped centrally in com.easytrading.backend.common.ApiExceptionHandler:
 *   NotAuthenticatedException     -> 401 NOT_AUTHENTICATED
 *   InstrumentNotFoundException   -> 404 NOT_FOUND
 *   AlreadyOnWatchlistException   -> 409 ALREADY_ON_WATCHLIST
 */
@RestController
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final SessionUser sessionUser;

    public WatchlistController(WatchlistService watchlistService, SessionUser sessionUser) {
        this.watchlistService = watchlistService;
        this.sessionUser = sessionUser;
    }

    @GetMapping("/api/watchlist")
    public WatchlistResponse list(HttpSession session) {
        User user = sessionUser.require(session);
        var items = watchlistService.list(user.getId()).stream()
                .map(WatchlistController::toResponse)
                .toList();
        // An empty list means "you have saved nothing", and stays distinct from
        // "nobody is logged in", which is the 401 above.
        return new WatchlistResponse(items);
    }

    @PostMapping("/api/watchlist")
    public ResponseEntity<InstrumentMatchResponse> add(@RequestBody WatchlistRequest request, HttpSession session) {
        User user = sessionUser.require(session);
        Instrument saved = watchlistService.add(user.getId(), request.symbol());
        // The saved instrument comes back so the frontend can render the new row
        // from the response instead of re-fetching the whole list.
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @DeleteMapping("/api/watchlist")
    public ResponseEntity<Void> remove(@RequestParam(value = "symbol", required = false) String symbol,
                                       HttpSession session) {
        User user = sessionUser.require(session);
        watchlistService.remove(user.getId(), symbol);
        // 204 whether or not it was there -- see WatchlistService.remove.
        return ResponseEntity.noContent().build();
    }

    private static InstrumentMatchResponse toResponse(Instrument instrument) {
        // Same lowercase type convention as /api/search (the
        // DB's own spelling).
        return new InstrumentMatchResponse(instrument.getSymbol(), instrument.getName(),
                instrument.getType().name().toLowerCase());
    }
}
