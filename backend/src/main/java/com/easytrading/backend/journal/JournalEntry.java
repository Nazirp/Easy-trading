package com.easytrading.backend.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One journal entry -- a note a user wrote, optionally attached to
 * an instrument and to one of their own trades.
 *
 * <h3>There is no price or signal on this row</h3>
 *
 * A {@link
 * com.easytrading.backend.trading.Trade} row already records the exact price and the
 * exact instant, captured by the server at execution. A snapshot stored here would be
 * a <b>second record of the same moment</b>, and when two records of one moment
 * disagree there is no way to tell afterwards which was right.
 *
 * <h3>Plain ids, not associations</h3>
 *
 * {@code userId}, {@code symbol} and {@code tradeId} are columns rather than
 * {@code @ManyToOne} references, for the same reason as {@code WatchlistEntry}: the
 * foreign keys are real and enforced by the database, and mapping them as objects
 * would let a caller walk from an entry to a whole {@code User}, password hash
 * included.
 *
 * <h3>Mutable, unlike every other entity here</h3>
 *
 * {@code body} and {@code updatedAt} have setters. The
 * link is different: an entry written without a trade can be linked to one later,
 * once, through {@link #linkTrade} -- and after that it never changes. There is
 * deliberately no setter for {@code symbol} or {@code tradeId}; see
 * {@link JournalService#update}.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "body", nullable = false)
    private String body;

    /** Null when the entry is about nothing in particular, which is the common case. */
    @Column(name = "symbol", length = 20)
    private String symbol;

    /**
     * Null unless the entry is about a specific trade. When it is set, {@code symbol}
     * is set too -- and is taken from the trade rather than from the request, because
     * the trade's instrument is a fact the server already holds.
     */
    @Column(name = "trade_id")
    private Long tradeId;

    /**
     * Set in Java rather than left to the column's {@code DEFAULT NOW()}, same as
     * {@code Trade.openedAt} and unlike {@code WatchlistEntry.addedAt}: the created
     * entry goes straight back in the 201 response, and a database default is not
     * visible to the entity until the row is re-read. The DB default stays as the
     * backstop for a row inserted by hand.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Null until the first edit, and deliberately NOT set equal to {@code createdAt}
     * on insert. Setting it would look tidier and would destroy the only thing this
     * column is for: telling "never edited" from "edited instantly".
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected JournalEntry() {
        // required by JPA
    }

    public JournalEntry(Long userId, String body, String symbol, Long tradeId,
                        LocalDateTime createdAt) {
        this.userId = userId;
        this.body = body;
        this.symbol = symbol;
        this.tradeId = tradeId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getBody() {
        return body;
    }

    public String getSymbol() {
        return symbol;
    }

    public Long getTradeId() {
        return tradeId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Replaces the text. Validation (non-blank, trimmed) lives in
     * {@link JournalService}, so that a blank body is a readable 400 rather than a
     * constraint violation surfacing as a 500.
     */
    public void setBody(String body) {
        this.body = body;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Links a trade to an entry that was written without one -- the only change to
     * its link there is. The symbol comes with it, taken from the trade.
     *
     * An entry that already has a trade keeps it: it records what somebody thought
     * about THAT trade, and re-pointing it afterwards would rewrite that silently.
     * {@link JournalService#update} checks first and answers 409; this is the
     * backstop, so no other caller can re-point one either.
     */
    public void linkTrade(Long tradeId, String symbol) {
        if (this.tradeId != null) {
            throw new IllegalStateException(
                    "Entry " + id + " is already linked to trade " + this.tradeId + ".");
        }
        this.tradeId = tradeId;
        this.symbol = symbol;
    }
}
