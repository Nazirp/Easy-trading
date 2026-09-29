package com.easytrading.backend.journal;

import com.easytrading.backend.instrument.InstrumentNotFoundException;
import com.easytrading.backend.instrument.InstrumentRepository;
import com.easytrading.backend.trading.Trade;
import com.easytrading.backend.trading.TradeRepository;
import com.easytrading.backend.trading.TradeDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SCRUM-81 — the journal's rules, with no Spring, no database and no Docker.
 *
 * Three of these tests are about things that are invisible if you only check the
 * status code: that a rejected entry costs no query, that an edit leaves the links
 * alone, and that a linked trade decides the symbol rather than the request. Each is
 * a property a later refactor could remove without any test failing, unless the test
 * looks at what the collaborators were asked to do rather than only at what came
 * back.
 *
 * The ownership rules are asserted here as arithmetic — the repository is told to
 * answer "empty" and the service must turn that into a 404-shaped exception — and
 * again over real HTTP in {@code JournalIntegrationTest}, which is where a mistake in
 * the wiring rather than in the logic would show.
 */
class JournalServiceTest {

    private static final Long ME = 7L;
    private static final Long SOMEONE_ELSE = 8L;

    private JournalRepository journalRepository;
    private InstrumentRepository instrumentRepository;
    private TradeRepository tradeRepository;
    private JournalService journalService;

    @BeforeEach
    void setUp() {
        journalRepository = mock(JournalRepository.class);
        instrumentRepository = mock(InstrumentRepository.class);
        tradeRepository = mock(TradeRepository.class);
        journalService = new JournalService(journalRepository, instrumentRepository, tradeRepository);
        // save() hands back whatever it was given, so the tests can inspect the entity
        // the service actually built instead of a Mockito null.
        when(journalRepository.save(any(JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Trade tradeOf(Long userId, String symbol) {
        return new Trade(userId, symbol, TradeDirection.LONG, new BigDecimal("0.001"),
                new BigDecimal("76000.00000"), LocalDateTime.now());
    }

    private JournalEntry saved() {
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---- the common case -------------------------------------------------

    @Test
    void anEntryWithNoSymbolAndNoTradeIsTheNormalCaseNotAnEdgeCase() {
        journalService.create(ME, "I keep buying tops. Slow down.", null, null);

        JournalEntry entry = saved();
        assertThat(entry.getUserId()).isEqualTo(ME);
        assertThat(entry.getBody()).isEqualTo("I keep buying tops. Slow down.");
        assertThat(entry.getSymbol()).isNull();
        assertThat(entry.getTradeId()).isNull();
        assertThat(entry.getCreatedAt()).isNotNull();
        // Null until the first edit -- this is the whole reason the column is not set
        // to created_at on insert.
        assertThat(entry.getUpdatedAt()).isNull();

        // Nothing was looked up, because nothing was linked.
        verifyNoInteractions(instrumentRepository);
        verifyNoInteractions(tradeRepository);
    }

    @Test
    void theBodyIsStoredTrimmed() {
        journalService.create(ME, "   spaces around it \n", null, null);

        assertThat(saved().getBody()).isEqualTo("spaces around it");
    }

    // ---- UC05 5a: an empty entry -----------------------------------------

    @Test
    void anEmptyBodyIsRejectedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> journalService.create(ME, "", "BTC/USD", 1L))
                .isInstanceOf(InvalidJournalEntryException.class);

        // The point of this assertion, and the reason it is not enough to check that
        // it threw: validating AFTER the lookups would also throw, and would also
        // pass a status-code test, while costing a query for every blank submission.
        verifyNoInteractions(journalRepository);
        verifyNoInteractions(instrumentRepository);
        verifyNoInteractions(tradeRepository);
    }

    @Test
    void aWhitespaceOnlyBodyIsNotAnEntryEither() {
        assertThatThrownBy(() -> journalService.create(ME, "   \n\t ", null, null))
                .isInstanceOf(InvalidJournalEntryException.class);
        assertThatThrownBy(() -> journalService.create(ME, null, null, null))
                .isInstanceOf(InvalidJournalEntryException.class);

        verifyNoInteractions(journalRepository);
    }

    // ---- links ------------------------------------------------------------

    @Test
    void aLinkedTradeDecidesTheSymbolAndTheRequestDoesNot() {
        when(tradeRepository.findByIdAndUserId(42L, ME))
                .thenReturn(Optional.of(tradeOf(ME, "BTC/USD")));

        // The caller says EUR/USD. The trade says BTC/USD. The trade wins.
        journalService.create(ME, "Sized this one properly.", "EUR/USD", 42L);

        JournalEntry entry = saved();
        assertThat(entry.getSymbol()).isEqualTo("BTC/USD");
        assertThat(entry.getTradeId()).isEqualTo(42L);
        // ...and the instrument table is never consulted, because the trade already
        // answers the question.
        verifyNoInteractions(instrumentRepository);
    }

    @Test
    void aTradeBelongingToSomebodyElseIsRefused() {
        // The repository is asked for (id, MY id) and finds nothing -- the row exists,
        // but not for me. The service cannot tell the difference and must not try.
        when(tradeRepository.findByIdAndUserId(42L, ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.create(ME, "nice trade", null, 42L))
                .isInstanceOf(LinkedTradeNotFoundException.class);

        verify(journalRepository, never()).save(any());
    }

    @Test
    void theTradeLookupAsksForTheSessionUserAndNotForAnythingTheCallerSent() {
        when(tradeRepository.findByIdAndUserId(42L, ME))
                .thenReturn(Optional.of(tradeOf(ME, "BTC/USD")));

        journalService.create(ME, "note", null, 42L);

        // The owner is part of the QUERY. If this ever becomes findById(42) followed
        // by a comparison, this line stops compiling -- which is the point.
        verify(tradeRepository).findByIdAndUserId(42L, ME);
        verify(tradeRepository, never()).findById(any());
    }

    @Test
    void anUnknownSymbolIsRejected() {
        when(instrumentRepository.existsById("NOPE/USD")).thenReturn(false);

        assertThatThrownBy(() -> journalService.create(ME, "note", "NOPE/USD", null))
                .isInstanceOf(InstrumentNotFoundException.class);

        verify(journalRepository, never()).save(any());
    }

    @Test
    void aKnownSymbolWithNoTradeIsKept() {
        when(instrumentRepository.existsById("BTC/USD")).thenReturn(true);

        journalService.create(ME, "watching this one", " BTC/USD ", null);

        assertThat(saved().getSymbol()).isEqualTo("BTC/USD");
        assertThat(saved().getTradeId()).isNull();
    }

    @Test
    void aBlankSymbolIsTreatedAsAbsentRatherThanAsAMissingInstrument() {
        journalService.create(ME, "note", "   ", null);

        assertThat(saved().getSymbol()).isNull();
        verifyNoInteractions(instrumentRepository);
    }

    // ---- editing ----------------------------------------------------------

    @Test
    void anEditChangesTheTextAndTheTimestampAndNothingElse() {
        JournalEntry existing = new JournalEntry(ME, "first thought", "BTC/USD", 42L,
                LocalDateTime.of(2026, 9, 20, 9, 0));
        when(journalRepository.findByIdAndUserId(5L, ME)).thenReturn(Optional.of(existing));

        journalService.update(ME, 5L, "second thought");

        assertThat(existing.getBody()).isEqualTo("second thought");
        assertThat(existing.getUpdatedAt()).isNotNull();
        // An entry records what somebody thought at a moment. Re-pointing it at a
        // different instrument or trade afterwards would rewrite that silently, and
        // the result would be indistinguishable from an entry written at the time.
        assertThat(existing.getSymbol()).isEqualTo("BTC/USD");
        assertThat(existing.getTradeId()).isEqualTo(42L);
        assertThat(existing.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 9, 0));
    }

    @Test
    void anEditToABlankBodyIsRejectedBeforeTheEntryIsEvenLookedUp() {
        assertThatThrownBy(() -> journalService.update(ME, 5L, "  "))
                .isInstanceOf(InvalidJournalEntryException.class);

        verifyNoInteractions(journalRepository);
    }

    @Test
    void editingSomebodyElsesEntryIsNotFoundRatherThanForbidden() {
        when(journalRepository.findByIdAndUserId(5L, ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.update(ME, 5L, "mine now"))
                .isInstanceOf(JournalEntryNotFoundException.class);
    }

    // ---- deleting ---------------------------------------------------------

    @Test
    void deletingSomebodyElsesEntryIsNotFoundAndDeletesNothing() {
        when(journalRepository.findByIdAndUserId(5L, ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.delete(ME, 5L))
                .isInstanceOf(JournalEntryNotFoundException.class);

        // Not idempotent, unlike removing from the watchlist: answering 204 here would
        // report a deletion that did not happen, and the user would believe their
        // writing was gone.
        verify(journalRepository, never()).delete(any());
    }

    @Test
    void deletingYourOwnEntryDeletesTheRowThatWasFoundByIdAndOwner() {
        JournalEntry mine = new JournalEntry(ME, "bye", null, null, LocalDateTime.now());
        when(journalRepository.findByIdAndUserId(5L, ME)).thenReturn(Optional.of(mine));

        journalService.delete(ME, 5L);

        verify(journalRepository).delete(mine);
        verify(journalRepository, never()).deleteById(any());
    }

    // ---- listing ----------------------------------------------------------

    @Test
    void theListIsAskedForByOwnerAndNewestFirst() {
        when(journalRepository.findByUserIdOrderByCreatedAtDescIdDesc(ME)).thenReturn(List.of());

        assertThat(journalService.list(ME)).isEmpty();

        // There is no unfiltered "find all entries" anywhere in this feature, and no
        // sort applied afterwards in Java -- the order is the index's job.
        verify(journalRepository).findByUserIdOrderByCreatedAtDescIdDesc(ME);
        verify(journalRepository, never()).findAll();
    }

    @Test
    void oneUsersIdIsNeverSubstitutedForAnothers() {
        when(journalRepository.findByIdAndUserId(5L, SOMEONE_ELSE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.update(SOMEONE_ELSE, 5L, "text"))
                .isInstanceOf(JournalEntryNotFoundException.class);

        verify(journalRepository, never()).findByIdAndUserId(eq(5L), eq(ME));
        verify(instrumentRepository, never()).existsById(anyString());
    }
}
