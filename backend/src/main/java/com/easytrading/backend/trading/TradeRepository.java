package com.easytrading.backend.trading;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Trades belong to a user, and every method here names the owner -- in the query,
 * not in a check afterwards. A caller cannot ask for a trade without saying whose it
 * is, so one user's rows cannot come back to another through a forgotten
 * {@code WHERE}. {@code JpaRepository} still inherits {@code findById}; nothing in
 * this feature calls it.
 */
public interface TradeRepository extends JpaRepository<Trade, Long> {

    /**
     * Every trade this user has in one instrument, newest first.
     *
     * The only list the application reads. The history shows it as it is; the account
     * block walks it once to split open trades (for the margin and the live P&amp;L)
     * from closed ones (for the realised total), which is why there is no separate
     * "open trades" query -- the poll needs the closed rows too. Ties break on
     * {@code id} because two trades can land in the same millisecond, and a list must
     * not reorder itself between two identical reads.
     */
    List<Trade> findByUserIdAndSymbolOrderByOpenedAtDescIdDesc(Long userId, String symbol);

    /**
     * One trade, but only if it is this user's. Empty covers both "no such trade" and
     * "not yours", and both become the same 404 -- a 403 would confirm the id exists.
     * Used by closing, and by the journal's trade link.
     */
    Optional<Trade> findByIdAndUserId(Long id, Long userId);

    /**
     * Closes a trade, if it is this user's and still open. Returns the number of rows
     * changed: 1 on success, 0 if it was already closed, missing, or someone else's.
     *
     * <b>This single statement is the double-close guard.</b> Two clicks, or two tabs,
     * both reach here with the trade open; under Postgres' row lock the second
     * {@code UPDATE} waits for the first to commit, re-reads the row, finds
     * {@code closed_at} set and matches nothing. The service credits cash only after a
     * 1, so the second request credits nothing.
     *
     * {@code clearAutomatically} because this bypasses the persistence context: any
     * {@code Trade} loaded earlier in the transaction is stale afterwards and must be
     * re-read rather than trusted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Trade t
               SET t.exitPrice = :price, t.closedAt = :closedAt
             WHERE t.id = :id AND t.userId = :userId AND t.closedAt IS NULL
            """)
    int close(@Param("id") Long id,
              @Param("userId") Long userId,
              @Param("price") BigDecimal price,
              @Param("closedAt") LocalDateTime closedAt);
}
