package com.easytrading.backend.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Trades belong to a user. Every query here filters on {@code userId}, which is the
 * point: a caller cannot ask for a trade without saying whose it is, so one user's
 * rows cannot be returned to another by a forgotten {@code WHERE} clause.
 *
 * <b>Updated 2026-09-23 (SCRUM-81).</b> This javadoc used to say there was "no find
 * by id used anywhere". There is one now -- {@link #findByIdAndUserId}, added for the
 * journal's trade link -- and it takes the owner as part of the query rather than as
 * a check afterwards, so the property the old sentence was describing still holds.
 * The sentence itself did not, which is why it was rewritten instead of left to be
 * believed.
 *
 * Both orderings break ties on {@code id}. Two trades can land in the same
 * millisecond, and the replay in {@link TradeService#positionFor} must be
 * deterministic -- otherwise the average cost could differ between two reads of the
 * same rows, which is the sort of thing that is impossible to reproduce once someone
 * reports it. {@code db/schema.sql}'s {@code get_position()} orders the same way.
 */
public interface TradeRepository extends JpaRepository<Trade, Long> {

    /** Oldest first: the order the position replay needs. */
    List<Trade> findByUserIdAndSymbolOrderByExecutedAtAscIdAsc(Long userId, String symbol);

    /** Newest first: the order the history list is displayed in. */
    List<Trade> findByUserIdAndSymbolOrderByExecutedAtDescIdDesc(Long userId, String symbol);

    /**
     * One trade, but only if it belongs to this user (SCRUM-81).
     *
     * Used when a journal entry links a trade. The owner is in the QUERY and not a
     * comparison made afterwards: without that, posting entries with {@code tradeId}
     * 1, 2, 3... and watching which are accepted would report how many trades other
     * people have placed. Empty covers "no such trade" and "not your trade" with one
     * answer, so the loop learns nothing either way.
     */
    Optional<Trade> findByIdAndUserId(Long id, Long userId);
}
