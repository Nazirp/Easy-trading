package com.easytrading.backend.journal;

/**
 * The `tradeId` on a new entry is not one of this user's trades.
 *
 * Also a 404 rather than a 403, and this one matters more than it looks: without
 * it, posting entries with `tradeId` 1, 2, 3... and watching which are accepted
 * would report how many trades other people have placed. Covering "no such trade"
 * and "not your trade" with one answer makes that loop tell the caller nothing.
 */
public class LinkedTradeNotFoundException extends RuntimeException {

    public LinkedTradeNotFoundException(String message) {
        super(message);
    }
}
