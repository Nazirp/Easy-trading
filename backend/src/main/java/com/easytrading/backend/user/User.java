package com.easytrading.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps 1:1 to the `app_user` table in db/schema.sql (SCRUM-67).
 *
 * The table is `app_user`, not `user`: `user` is reserved in SQL and a built-in
 * function in Postgres, so it would need quoting everywhere. The class keeps the
 * natural name.
 *
 * There is no `password` field and never will be — only `passwordHash`. Nothing
 * in this class can hand out a plaintext password because it never holds one:
 * hashing happens in AuthService before the entity is built.
 */
@Entity
@Table(name = "app_user")
public class User {

    /**
     * The starting virtual balance for a new account (UC04 BR1). The column has
     * the same DEFAULT in db/schema.sql, but this is the authoritative copy —
     * same rule as the read functions in schema.sql: where the two could drift,
     * the Java is what runs. The DB default is a backstop for rows inserted by
     * hand.
     */
    public static final BigDecimal STARTING_CASH = new BigDecimal("10000.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "username", length = 50, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "cash_balance", nullable = false, precision = 18, scale = 5)
    private BigDecimal cashBalance;

    /**
     * Written by the database's own DEFAULT (NOW() AT TIME ZONE 'UTC'), never by
     * Hibernate — that is what keeps every row on one clock regardless of the
     * server's timezone, the same convention the intraday candles follow. The
     * consequence is that this field is null on a freshly saved instance until
     * the row is read back; nothing needs it in a response, so that is fine.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected User() {
        // required by JPA
    }

    public User(String username, String passwordHash) {
        this(username, passwordHash, STARTING_CASH);
    }

    public User(String username, String passwordHash, BigDecimal cashBalance) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.cashBalance = cashBalance;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public BigDecimal getCashBalance() {
        return cashBalance;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
