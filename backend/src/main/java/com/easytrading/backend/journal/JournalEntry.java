package com.easytrading.backend.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One journal entry (UC05, SCRUM-81) -- a note a user wrote, optionally attached to
 * an instrument and to one of their own trades.
 *
 * <h3>There is no price or signal on this row, and that is the decision</h3>
 *
 * UC05 step 6 originally said that submitting an entry snapshots the instrument's
 * current price and signal. It was dropped on 2026-09-22, before any of it was built,
 * because {@code trade_id} makes it redundant where it matters: a {@link
 * com.easytrading.backend.trading.Trade} row already records the exact price and the
 * exact instant, captured by the server at execution. A snapshot stored here would be
 * a <b>second record of the same moment</b>, and when two records of one moment
 * disagree there is no way to tell afterwards which was right.
 *
 * The accepted cost: an entry that names an instrument but links no trade does not
 * record what that instrument was worth when it was written. An entry that wants to
 * say "this is what the market was doing" links a trade; one that does not is a note,
 * and a note does not need a price.
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
 * {@code body} and {@code updatedAt} have setters because UC05 allows editing. Only
 * those two. There is deliberately no setter for {@code symbol} or {@code tradeId} --
 * see {@link JournalService#update}.
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
}
