package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;

/**
 * What {@link LivePriceService} hands back: a price, plus whether it is the one
 * just fetched or the last good one being re-served because the provider failed.
 *
 * `outdated` exists so the honesty of the number survives the trip to the
 * browser. UC04 6a/6b require the page to keep showing the last price with a
 * small "may be outdated" note rather than blanking out or throwing an error, and
 * the frontend cannot work that out on its own -- a repeated price is normal
 * when nothing traded, so it is not a signal.
 */
public record LiveQuote(LivePrice price, boolean outdated) {}
