package com.easytrading.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Data access layer for accounts.
 *
 * Note what is NOT here: no query that takes a password. Verification happens in
 * AuthService with the PasswordEncoder — a password that reaches a SQL statement
 * ends up in the query log and in pg_stat_statements, which is exactly what
 * hashing was supposed to prevent. db/schema.sql deliberately has no login
 * function for the same reason.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /**
     * Moves cash by {@code delta} -- negative to take, positive to give -- unless that
     * would leave the balance below zero. Returns the number of rows changed: 1 if it
     * happened, 0 if it would have gone negative (or the user is gone).
     *
     * <b>The only way cash changes</b> (SCRUM-83). A relative update rather than
     * read-check-write: two concurrent trades each add their own delta to whatever the
     * other committed, instead of both reading the same balance and one overwriting the
     * other. And the {@code >= 0} guard makes "you cannot spend more than you have" one
     * atomic statement rather than a check that a second request can slip past.
     *
     * {@code clearAutomatically} because this bypasses the persistence context: a
     * {@code User} loaded earlier in the transaction holds the old balance afterwards
     * and must be re-read.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u
               SET u.cashBalance = u.cashBalance + :delta
             WHERE u.id = :id AND u.cashBalance + :delta >= 0
            """)
    int adjustCash(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
