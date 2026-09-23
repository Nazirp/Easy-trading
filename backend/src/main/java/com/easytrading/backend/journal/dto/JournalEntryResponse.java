package com.easytrading.backend.journal.dto;

import java.time.Instant;

/**
 * One journal entry as the frontend receives it.
 *
 * `symbol` and `tradeId` are null when the entry is about nothing in particular.
 * `updatedAt` is null until the entry has actually been edited -- the frontend can
 * therefore show "edited" from the presence of the field alone, which is the whole
 * reason the column is not set to createdAt on insert.
 *
 * `createdAt` and `updatedAt` are real zoned instants -- do not append a `Z`. Same
 * rule as `executedAt` on /api/trades and `priceAt` on /api/getLiveChart, and the
 * opposite of `datetime` on /api/getPrice, which is zone-less UTC and does need one.
 */
public record JournalEntryResponse(Long id,
                                   String body,
                                   String symbol,
                                   Long tradeId,
                                   Instant createdAt,
                                   Instant updatedAt) {
}
