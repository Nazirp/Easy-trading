package com.easytrading.backend.liveprice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Body of {@code GET /api/getLiveChart} — the entire demo-trading chart in one
 * response, polled once a second.
 *
 * <h3>One endpoint on purpose</h3>
 *
 * The page previously needed {@code /api/getLiveHistory} for the past and
 * {@code /api/getLivePrice} for the readout, and had to stitch them together
 * itself — which meant two round trips per cycle and two chances for the number
 * and the chart to disagree. Everything the page draws now comes from one
 * snapshot taken at one instant, so they cannot.
 *
 * <b>`price` is the same number as the last candle's close.</b> It is stated
 * separately only so the readout does not have to reach into the array and
 * handle it being empty.
 *
 * @param candleSeconds  stated rather than assumed, so the bucket length stays a
 *                       server decision the frontend reads instead of a constant
 *                       duplicated in two languages
 * @param candles        oldest first; an EMPTY list is a normal 200 meaning "no
 *                       data yet", not an error
 * @param price          the latest price, or null when there are no candles
 * @param priceAt        when that price was true
 * @param outdated       true when no trade has arrived recently — the socket has
 *                       dropped, is reconnecting, or never started. The chart
 *                       stays on screen; the page shows a "may be outdated" note
 */
public record LiveChartResponse(String symbol,
                                int candleSeconds,
                                List<LiveChartCandle> candles,
                                BigDecimal price,
                                Instant priceAt,
                                boolean outdated) {}
