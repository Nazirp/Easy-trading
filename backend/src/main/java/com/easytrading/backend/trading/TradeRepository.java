package com.easytrading.backend.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Trades belong to a user. Both queries filter on {@code userId} first and there is
 * no "find by id" used anywhere, which is the point: a caller cannot ask for a trade
 * without saying whose it is, so one user's rows cannot be returned to another by a
 * forgotten {@code WHERE} clause.
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
}
