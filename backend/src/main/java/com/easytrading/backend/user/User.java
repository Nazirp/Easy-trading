package com.easytrading.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Maps to the `app_user` table in db/schema.sql.
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
     * The starting virtual balance for a new account. The column has
     * the same DEFAULT in db/schema.sql, but this is the authoritative copy.
     * The DB default is a backstop for rows inserted by hand.
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
}
