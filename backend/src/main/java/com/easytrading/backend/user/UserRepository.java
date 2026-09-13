package com.easytrading.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
