package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;

/**
 * What {@link LivePriceService} hands back: a price, plus whether it is the one
 * just fetched or the last good one being re-served because the provider failed.
 */
public record LiveQuote(LivePrice price, boolean outdated) {}
