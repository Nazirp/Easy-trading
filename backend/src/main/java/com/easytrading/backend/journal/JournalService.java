package com.easytrading.backend.journal;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.trading.Trade;
import com.easytrading.backend.trading.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Business logic layer for UC05 -- the trading journal.
 *
 * <h3>What this package does NOT depend on</h3>
 *
 * {@code user}, {@code instrument} and {@code trading} -- and deliberately not
 * {@code price} or {@code liveprice}. The dropped price snapshot was the only thing
 * that would have made a journal write reach into the price stack, and with it gone
 * there is no upstream call to make. That also retires a rule this feature was
 * written with: <i>a journal write must never trigger a fetch</i>, because writing a
 * diary entry is no reason to spend from a budget of 800 requests a day. The rule is
 * not enforced; it is unnecessary. <b>The simplest way to satisfy a constraint is to
 * not need it</b> -- a rule that must be remembered is one the next feature will
 * forget, where a dependency that does not exist cannot be misused.
 *
 * <h3>Every method names the owner</h3>
 *
 * The user id is always the first argument and always comes from the session, never
 * from anything the caller sent. More than that, it is part of every <i>query</i>
 * rather than checked after the fact -- see {@link JournalRepository}.
 */
@Service
public class JournalService {

    private final JournalRepository journalRepository;
    private final InstrumentRepository instrumentRepository;
    private final TradeRepository tradeRepository;

    public JournalService(JournalRepository journalRepository,
                          InstrumentRepository instrumentRepository,
                          TradeRepository tradeRepository) {
        this.journalRepository = journalRepository;
        this.instrumentRepository = instrumentRepository;
        this.tradeRepository = tradeRepository;
    }

    /** This user's entries, newest first. An empty list is a normal answer, not an error. */
    @Transactional(readOnly = true)
    public List<JournalEntry> list(Long userId) {
        return journalRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    /**
     * Writes a new entry.
     *
     * <h3>When a trade is linked, the symbol comes from the trade</h3>
     *
     * Any {@code symbol} in the request is ignored in that case. The trade already
     * records which instrument it was, so accepting the client's word for it would
     * create a pair that can disagree -- an entry filed under EUR/USD attached to a
     * BTC/USD trade, with nothing able to say afterwards which was meant. This is the
     * journal's instance of the rule the rest of the codebase already follows twice:
     * <b>anything the server can determine, the server determines.</b>
     *
     * @param userId    from the session, never from the request
     * @param rawBody   the note; trimmed before storing, because leading and trailing
     *                  whitespace is not content
     * @param rawSymbol optional instrument, ignored when {@code tradeId} is given
     * @param tradeId   optional trade, which must be one of this user's
     *
     * @throws InvalidJournalEntryException the body is missing, empty or only whitespace (400)
     * @throws InstrumentNotFoundException  no such instrument (404)
     * @throws LinkedTradeNotFoundException the trade is not this user's, or does not exist (404)
     */
    @Transactional
    public JournalEntry create(Long userId, String rawBody, String rawSymbol, Long tradeId) {
        // Validated BEFORE anything is looked up, so a blank entry cannot cost a
        // query -- and so the test can assert that nothing was written rather than
        // only that something was thrown.
        String body = requireBody(rawBody);

        String symbol = null;
        if (tradeId != null) {
            Trade trade = tradeRepository.findByIdAndUserId(tradeId, userId)
                    .orElseThrow(() -> new LinkedTradeNotFoundException(
                            "No trade found for id " + tradeId + "."));
            symbol = trade.getSymbol();
        } else if (rawSymbol != null && !rawSymbol.isBlank()) {
            String candidate = rawSymbol.trim();
            // Existence is checked here rather than left to the foreign key for the
            // same reason as the body: a constraint violation is a 500, and "no such
            // instrument" is a sentence the user can act on.
            if (!instrumentRepository.existsById(candidate)) {
                throw new InstrumentNotFoundException("No instrument found for '" + candidate + "'.");
            }
            symbol = candidate;
        }

        return journalRepository.save(
                new JournalEntry(userId, body, symbol, tradeId, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * Edits the text of an entry, and nothing else.
     *
     * <b>Not the symbol, and not the trade link.</b> An entry records what somebody
     * thought at a moment; re-pointing it at a different trade afterwards would
     * quietly rewrite that, and the edited entry would be indistinguishable from one
     * written about that trade at the time. The narrow signature is the enforcement --
     * there is no parameter with which to ask for more.
     *
     * @throws InvalidJournalEntryException   the new body is blank (400)
     * @throws JournalEntryNotFoundException  no such entry, or it is not this user's (404)
     */
    @Transactional
    public JournalEntry update(Long userId, Long id, String rawBody) {
        String body = requireBody(rawBody);

        JournalEntry entry = requireOwn(userId, id);
        entry.setBody(body);
        entry.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return journalRepository.save(entry);
    }

    /**
     * Deletes an entry.
     *
     * <b>Not idempotent, unlike {@code WatchlistService.remove}</b>, and the
     * difference is worth stating because the two look like the same operation. A
     * watchlist row is named by something the user chose -- "remove EUR/USD" -- so
     * succeeding when it is already gone tells them the truth. An entry is named by
     * an opaque id, so answering 204 for an id that is not theirs would report a
     * deletion that did not happen, and the user would believe their writing was
     * gone. A 404 is the honest answer, and it is the same 404 whether the entry is
     * missing or somebody else's.
     *
     * @throws JournalEntryNotFoundException no such entry, or it is not this user's (404)
     */
    @Transactional
    public void delete(Long userId, Long id) {
        journalRepository.delete(requireOwn(userId, id));
    }

    // ---- shared guards ----------------------------------------------------

    /**
     * UC05 5a. Trimmed, because " " is not an entry and neither is a body that is
     * only a newline -- and the stored text is the trimmed one, so the database's
     * CHECK on the trimmed length can never be the thing that rejects a write that
     * got this far.
     */
    private static String requireBody(String rawBody) {
        String body = rawBody == null ? "" : rawBody.trim();
        if (body.isEmpty()) {
            throw new InvalidJournalEntryException("A journal entry needs some text.");
        }
        return body;
    }

    /** The only way this service reaches an entry: by id AND owner, in one query. */
    private JournalEntry requireOwn(Long userId, Long id) {
        if (id == null) {
            throw new JournalEntryNotFoundException("No journal entry found.");
        }
        return journalRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new JournalEntryNotFoundException(
                        "No journal entry found for id " + id + "."));
    }
}
