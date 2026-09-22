package com.easytrading.backend.liveprice.dto;

import java.util.List;

/**
 * Body of {@code GET /api/getLiveHistory} -- see backend/CONTRACTS.md section 1.
 *
 * `points` is oldest first. An EMPTY list is a valid answer and means "history is
 * unavailable right now" (UC04 extension 5a): the page opens with an empty chart
 * and a short note, then fills from the live feed. It is never a 404 and never a
 * 500, because a missing past does not stop the present from being tradeable.
 */
public record LiveHistoryResponse(String symbol, List<LivePointResponse> points) {}
