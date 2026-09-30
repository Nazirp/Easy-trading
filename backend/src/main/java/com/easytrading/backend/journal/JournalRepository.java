package com.easytrading.backend.journal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

/**
 * Every method here names the owner, and that is the single most important thing
 * about this class.
 *
 * <h3>Why {@code findByIdAndUserId} and never {@code findById}</h3>
 *
 * A journal entry id is a small integer, so it is guessable -- entry 41 is almost
 * certainly somebody's. The two shapes below look equivalent and are not:
 *
 * <pre>
 * // Right: the row is unreachable unless it is yours.
 * journalRepository.findByIdAndUserId(id, userId)
 *
 * // Wrong: findById(id), then compare entry.getUserId() in Java.
 * </pre>
 *
 * The second reads someone else's writing into memory before deciding it is not
 * allowed, which is one careless log line or error message away from leaking it, and
 * it invites a later refactor to drop the check "because the controller already
 * validates". The first makes the wrong answer <b>structurally unavailable</b>.
 *
 * {@code JpaRepository} does inherit {@code findById}. Nothing in this feature calls
 * it.
 */
public interface JournalRepository extends JpaRepository<JournalEntry, Long> {

    /**
     * One user's entries, newest first.
     *
     * Ties break on {@code id} descending for the same reason as
     * {@code TradeRepository}: two entries can be written in the same millisecond,
     * and without a tiebreak the list can come back in a different order on two
     * identical reads, which looks like the journal shuffling itself.
     */
    List<JournalEntry> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /**
     * One entry, but only if it belongs to this user. Empty covers both "no such
     * entry" and "not yours", which is exactly what the API should not distinguish --
     * see {@link JournalService}.
     */
    Optional<JournalEntry> findByIdAndUserId(Long id, Long userId);
}
