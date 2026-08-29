package com.easytrading.backend.liveprice;

import com.easytrading.backend.liveprice.dto.LivePrice;

/**
 * Backend <-> Finnhub (SCRUM-36). Paper contract only -- no implementation
 * class, nothing wired or called anywhere in MS3. Defined now so the
 * agreed shape exists in code for MS4 to build against; implemented and
 * polled every 5s once demo trading (UC04) is actually built.
 */
public interface LivePriceClient {
    LivePrice getLivePrice(String symbol);
}
