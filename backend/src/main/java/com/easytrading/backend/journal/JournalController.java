package com.easytrading.backend.journal;

import com.easytrading.backend.journal.dto.CreateJournalEntryRequest;
import com.easytrading.backend.journal.dto.JournalEntryResponse;
import com.easytrading.backend.journal.dto.JournalResponse;
import com.easytrading.backend.journal.dto.UpdateJournalEntryRequest;
import com.easytrading.backend.user.SessionUser;
import com.easytrading.backend.user.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * The trading journal -- see backend/CONTRACTS.md.
 *
 * <ul>
 *   <li>{@code POST /api/journal} -- write an entry. 201 with the created entry.</li>
 *   <li>{@code GET /api/journal} -- this user's entries, newest first.</li>
 * <li>{@code PATCH /api/journal/{id}} -- edit the text, and link a trade if there is none
 * yet.</li>
 *   <li>{@code DELETE /api/journal/{id}} -- remove it. 204.</li>
 * </ul>
 *
 * <h3>Why an id in the path here, when the watchlist uses a query parameter</h3>
 *
 * The watchlist rule exists because <i>symbols</i> contain slashes: {@code BTC/USD}
 * does not survive a path segment, and an encoded {@code %2F} is rejected by Tomcat
 * by default. An entry id is a number, so none of that applies. The rule was never
 * "paths are forbidden".
 *
 * <h3>There is no GET /api/journal/&#123;id&#125;</h3>
 *
 * The list is the only read. A single-entry endpoint would be one more owner-scoped
 * path to get right for a page that already holds every entry it can display.
 *
 * <h3>404, never 403</h3>
 *
 * An entry belonging to someone else is answered exactly as one that does not exist.
 * A 403 confirms the id is real, which is what walking the id space is looking for.
 * The scoping itself happens in the query, not in a comparison here -- see
 * {@link JournalRepository}.
 *
 * Every method begins with {@code sessionUser.require(session)}, and none of them
 * takes a user id, so no caller can name a different user.
 *
 * Errors are mapped centrally in com.easytrading.backend.common.ApiExceptionHandler:
 *   NotAuthenticatedException       -&gt; 401 NOT_AUTHENTICATED
 *   InvalidJournalEntryException    -&gt; 400 INVALID_BODY
 *   InstrumentNotFoundException     -&gt; 404 NOT_FOUND
 *   LinkedTradeNotFoundException    -&gt; 404 NOT_FOUND
 *   JournalEntryNotFoundException   -&gt; 404 NOT_FOUND
 *   JournalEntryAlreadyLinkedException -&gt; 409 ALREADY_LINKED
 */
@RestController
public class JournalController {

    private final JournalService journalService;
    private final SessionUser sessionUser;

    public JournalController(JournalService journalService, SessionUser sessionUser) {
        this.journalService = journalService;
        this.sessionUser = sessionUser;
    }

    @PostMapping("/api/journal")
    public ResponseEntity<JournalEntryResponse> create(@RequestBody CreateJournalEntryRequest request,
                                                       HttpSession session) {
        User user = sessionUser.require(session);

        JournalEntry entry = journalService.create(user.getId(), request.body(),
                request.symbol(), request.tradeId());

        // The created entry comes back whole so the page can render the new row from
        // the response instead of re-fetching the list -- same as POST /api/watchlist.
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(entry));
    }

    @GetMapping("/api/journal")
    public JournalResponse list(HttpSession session) {
        User user = sessionUser.require(session);

        var entries = journalService.list(user.getId()).stream()
                .map(JournalController::toResponse)
                .toList();

        return new JournalResponse(entries);
    }

    @PatchMapping("/api/journal/{id}")
    public JournalEntryResponse update(@PathVariable("id") Long id,
                                       @RequestBody UpdateJournalEntryRequest request,
                                       HttpSession session) {
        User user = sessionUser.require(session);
        // PATCH rather than PUT because the request is not the whole entry: it cannot
        // set the symbol, it can only ADD a trade link, and a PUT that silently ignores
        // most of a resource is a lie about what it does.
        return toResponse(journalService.update(user.getId(), id, request.body(), request.tradeId()));
    }

    @DeleteMapping("/api/journal/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id, HttpSession session) {
        User user = sessionUser.require(session);
        journalService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The one place a stored {@code LocalDateTime} becomes an {@code Instant}.
     *
     * The columns are {@code TIMESTAMP} and hold UTC by the schema's convention, so
     * the entity mirrors them as zone-less values. The API's rule is the other one --
     * a moment carries a zone -- so the conversion happens here, at the boundary,
     * rather than being left for the frontend to guess at. Same as
     * {@code TradeController.toResponse}.
     *
     * {@code updatedAt} stays null when it is null, rather than falling back to
     * {@code createdAt}: "never edited" is the information the column exists to carry.
     */
    private static JournalEntryResponse toResponse(JournalEntry entry) {
        return new JournalEntryResponse(entry.getId(), entry.getBody(), entry.getSymbol(),
                entry.getTradeId(),
                entry.getCreatedAt().toInstant(ZoneOffset.UTC),
                entry.getUpdatedAt() == null ? null : entry.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
