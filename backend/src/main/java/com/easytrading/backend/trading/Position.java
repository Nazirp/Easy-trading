package com.easytrading.backend.trading;

import java.math.BigDecimal;

/**
 * What a user currently holds of one instrument: a quantity and the average price
 * they paid for it.
 *
 * <b>Derived, never stored.</b> There is no {@code position} table -- this is the
 * result of replaying the user's trade rows in order. The alternative, a row kept in
 * step on every execution, is two copies of one fact that can disagree, and a
 * position that has drifted from the trades behind it is invisible until the numbers
 * stop adding up. See db/schema.sql.
 *
 * <b>Average-cost method.</b> A buy moves the weighted average; a sell reduces the
 * quantity and leaves the average alone. FIFO lot tracking is what a real broker
 * does and is more accurate about realised gains, but "your average price" jumping
 * every time you sell part of a holding is exactly the kind of thing UC04's audience
 * cannot be expected to interpret.
 *
 * Selling out completely resets {@code averageCost} to zero rather than leaving the
 * last one behind, so "no position" is one state and not two.
 *
 * @param quantity    units held, scale 8 (crypto needs it -- see {@link Trade})
 * @param averageCost weighted average price paid, scale 5, zero when nothing is held
 */
public record Position(BigDecimal quantity, BigDecimal averageCost) {

    public boolean isEmpty() {
        return quantity.signum() <= 0;
    }
}
