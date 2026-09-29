package com.easytrading.backend.trading;

/**
 * No trade with this id belongs to the logged-in user.
 *
 * <b>One exception for two situations</b> -- the trade does not exist, or it is
 * somebody else's -- and one answer for both, a 404 with the same message. A 403 would
 * confirm the id is real, which is exactly what walking a small integer id space is
 * looking for. The ownership check itself happens in the query
 * ({@link TradeRepository#findByIdAndUserId}), not in a comparison afterwards.
 */
public class TradeNotFoundException extends RuntimeException {
    public TradeNotFoundException(String message) {
        super(message);
    }
}
