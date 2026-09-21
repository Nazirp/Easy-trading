package com.easytrading.backend.liveprice.dto;

import com.easytrading.backend.trading.dto.AccountResponse;

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
 * @param account        the caller's cash, position and unrealised P&amp;L, valued
 *                       against the SAME price as the candles above (SCRUM-79).
 *                       <b>null</b> for a user who has never traded this
 *                       instrument — render "no open position", not a zero P&amp;L
 *
 * <h3>Why the account rides along here</h3>
 *
 * P&amp;L moves on every tick and the page already polls this endpoint once a
 * second. A separate {@code /api/account} — which CONTRACTS.md promised from MS3
 * until SCRUM-79 and which is deliberately not being built — would mean two polls
 * a second and, worse, a P&amp;L computed from a different price than the chart is
 * drawing. That is precisely the defect SCRUM-76 removed from the price readout,
 * and re-introducing it one feature later under another name would be the clearest
 * possible demonstration of not having learned anything.
 *
 * The cost, stated: this endpoint now touches the database once a second per open
 * page, where before it was pure memory. One indexed query over a handful of rows,
 * so it is affordable — but it is a real change in what this endpoint is.
 *
 * This is the only place {@code liveprice} depends on {@code trading}. The
 * dependency runs one way at the class level — nothing in {@code trading} imports
 * this package's DTOs — and it exists because one screen needs both, which is the
 * decision itself rather than an accident of wiring.
 */
public record LiveChartResponse(String symbol,
                                int candleSeconds,
                                List<LiveChartCandle> candles,
                                BigDecimal price,
                                Instant priceAt,
                                boolean outdated,
                                AccountResponse account) {}
