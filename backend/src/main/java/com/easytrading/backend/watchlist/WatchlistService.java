package com.easytrading.backend.watchlist;

import com.easytrading.backend.instrument.Instrument;
import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Business logic layer — the account-scoped watchlist.
 *
 * Every method takes the user id as its first argument, and that id always
 * comes from the session (SessionUser), never from anything the caller sent.
 * The controller has no way to name a different user and neither does the
 * repository: there is exactly one query that reads watchlist rows and it
 * filters on this id.
 */
@Service
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final InstrumentRepository instrumentRepository;

    public WatchlistService(WatchlistRepository watchlistRepository, InstrumentRepository instrumentRepository) {
        this.watchlistRepository = watchlistRepository;
        this.instrumentRepository = instrumentRepository;
    }

    /** The user's saved instruments, oldest first. Empty is a normal answer, not an error. */
    public List<Instrument> list(Long userId) {
        return watchlistRepository.findInstrumentsForUser(userId);
    }

    /**
     * Saves an instrument to the user's watchlist and returns it.
     *
     * @throws InstrumentNotFoundException  no such instrument (404) — also covers a
     *         missing or blank symbol, which matches nothing
     * @throws AlreadyOnWatchlistException  the user already saved it (409)
     */
    public Instrument add(Long userId, String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim();

        Instrument instrument = instrumentRepository.findById(symbol)
                .orElseThrow(() -> new InstrumentNotFoundException("No instrument found for '" + symbol + "'."));

        // Checked up front so the common case gets the right message, but the
        // primary key below is what actually guarantees it: two requests can
        // both pass this check before either one inserts.
        if (watchlistRepository.existsById(new WatchlistEntryId(userId, symbol))) {
            throw new AlreadyOnWatchlistException(symbol + " is already on your watchlist.");
        }

        try {
            watchlistRepository.saveAndFlush(new WatchlistEntry(userId, symbol));
        } catch (DataIntegrityViolationException ex) {
            // The composite primary key fired -- the same user added the same
            // instrument twice, concurrently. Without this catch it surfaces as
            // a 500; the frontend needs the same 409 it would have got a
            // millisecond earlier. saveAndFlush rather than save so the insert
            // happens inside this try instead of at transaction commit, after
            // the method has already returned. Same shape as
            // AuthService.register.
            throw new AlreadyOnWatchlistException(symbol + " is already on your watchlist.");
        }
        return instrument;
    }

    /**
     * Removes an instrument from the user's watchlist.
     *
     * Idempotent on purpose: removing something that is not there succeeds
     * silently. The caller asked for it to be gone, and it is gone — reporting
     * an error would tell them nothing they can act on, and would make a
     * double-click on the remove button look like a failure.
     */
    public void remove(Long userId, String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim();
        if (symbol.isEmpty()) {
            return;
        }
        WatchlistEntryId id = new WatchlistEntryId(userId, symbol);
        if (watchlistRepository.existsById(id)) {
            watchlistRepository.deleteById(id);
        }
    }
}
