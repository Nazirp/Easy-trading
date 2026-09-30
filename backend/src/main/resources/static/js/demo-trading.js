// The demo trading page: live BTC/USD candlestick chart plus long/short
// trading, balance and P&L.
//
// Talks to the live-chart half of the backend contract:
//   GET /api/getLiveChart?symbol=BTC/USD
//     -> 200 {symbol, candleSeconds, candles:[{start, open, high, low,
//              close, live, forming}], price, priceAt, outdated} | 401 | 404
//
// This covers the chart half and trading half below it: the account
// strip, open positions, order panel and trade history, all reading off
// the same getLiveChart poll plus POST /api/trades,
// POST /api/trades/{id}/close and GET /api/trades (backend/CONTRACTS.md).
// This page only ever shows BTC/USD: no instrument switcher, no range
// buttons, nothing borrowed from search.js (which this file deliberately
// does not touch, same as search.js and auth.js don't touch each other).
//
// Six things worth knowing before changing anything here:
//
//  1. This page is login-gated, but auth.js knows nothing about that --
//     it just renders #account-bar and fires
//     "easytrading:authchange" exactly as it does on the search page. All
//     the gating (show the chart vs. a "please log in" prompt, start vs.
//     stop polling) lives here, listening to that one event.
//
//  2. The "Z" trap: `start` and `priceAt` on THIS endpoint are already
//     zoned instants (they end in "Z"), so `new Date(c.start)` /
//     `Date.parse(c.start)` is correct as-is -- do NOT append a "Z"
//     (getPrice's candle `datetime` still needs it). The field is named
//     `start`, not `datetime`, specifically so the two conventions are
//     told apart by name rather than by memory.
//
//  3. Every poll redraws the chart from whatever `candles` it receives,
//     full stop. `outdated` means only "no trade has arrived recently, the
//     feed may have stalled" -- a normal 200, shown as a small notice,
//     never a reason to blank the chart.
//
//  4. Only the last candle (`forming: true`, at most one, always last)
//     still has room to change -- everything before it is sealed. Because
//     the whole array is redrawn every poll rather than diffed, this falls
//     out for free.
//
//  5. A gap is real and stays a gap. If a minute saw no trades, the server
//     sends no candle for it at all -- `start` values can jump by more than
//     `candleSeconds`. Candles are positioned on the X axis by their real
//     `start` time (not by array index), so a missing minute shows up as
//     genuine blank space rather than being hidden.
//
//  6. Nothing here is ever a hard error. An empty `candles` array is a
//     normal 200 (server just started, or Twelve Data is unreachable) --
//     draw nothing, show a short notice, keep polling. A failed or non-200
//     poll keeps whatever chart is already drawn, shows "may be outdated",
//     and tries again on the next 1-second tick. Polling pauses while the
//     tab is hidden (document.visibilityState) and resumes -- with an
//     immediate poll, not a wait -- when it becomes visible again.

(function () {
  "use strict";

  const SYMBOL = "BTC/USD";
  const POLL_MS = 1000;
  const STALE_MESSAGE = "Live price feed may be outdated — retrying…";
  const VIEW_CANDLES = 30; // how many one-minute candles are visible at
                            // once -- fixed, matches the backend's own
                            // 30-minute window size.

  const gate = document.getElementById("dt-gate");
  const chartSection = document.getElementById("dt-chart-section");
  const priceEl = document.getElementById("dt-price");
  const changeIconEl = document.getElementById("dt-change-icon");
  const staleNotice = document.getElementById("dt-stale-notice");
  const historyNotice = document.getElementById("dt-history-notice");
  const chartContainer = document.getElementById("dt-chart-container");
  const jumpLiveButton = document.getElementById("dt-jump-live");

  const tradingSection = document.getElementById("dt-trading-section");
  const positionsEmpty = document.getElementById("dt-positions-empty");
  const positionsList = document.getElementById("dt-positions-list");
  const closeError = document.getElementById("dt-close-error");
  const closeConfirm = document.getElementById("dt-close-confirm");

  const directionLongButton = document.getElementById("dt-direction-long");
  const directionShortButton = document.getElementById("dt-direction-short");
  const unitUsdButton = document.getElementById("dt-unit-usd");
  const unitQtyButton = document.getElementById("dt-unit-qty");
  const quantityLabel = document.getElementById("dt-quantity-label");
  const quantityInput = document.getElementById("dt-quantity");
  const maxButton = document.getElementById("dt-max");
  const quickfillButtons = document.querySelectorAll(".dt-quickfill-button");
  const previewQuantityEl = document.getElementById("dt-preview-quantity");
  const previewPriceEl = document.getElementById("dt-preview-price");
  const previewMarginEl = document.getElementById("dt-preview-margin");
  const submitButton = document.getElementById("dt-submit-trade");
  const tradeError = document.getElementById("dt-trade-error");
  const tradeConfirm = document.getElementById("dt-trade-confirm");
  const availableValueEl = document.getElementById("dt-available-value");

  const historyEmpty = document.getElementById("dt-history-empty");
  const historyList = document.getElementById("dt-history-list");
  const historyDirectionInput = document.getElementById("dt-history-direction");
  const historyDateInput = document.getElementById("dt-history-date");

  const tradeDialog = document.getElementById("dt-trade-dialog");
  const tradeDialogTitle = document.getElementById("dt-trade-dialog-title");
  const tradeDialogResult = document.getElementById("dt-trade-dialog-result");
  const tradeDialogFacts = document.getElementById("dt-trade-dialog-facts");
  const tradeDialogClose = document.getElementById("dt-trade-dialog-close");
  const tradeDialogJournal = document.getElementById("dt-trade-dialog-journal");

  const topbarStats = document.getElementById("dt-topbar-stats");
  const topbarTotalEl = document.getElementById("dt-topbar-total");
  const topbarCashEl = document.getElementById("dt-topbar-cash");
  const topbarMarginEl = document.getElementById("dt-topbar-margin");
  const topbarPnlEl = document.getElementById("dt-topbar-pnl");

  // Same icon+colour pairing convention as the signal badge --
  // never colour-only, so a red-green colourblind viewer can still tell an
  // uptick from a downtick.
  const CHANGE_ICONS = { up: "▲", down: "▼", flat: "●" };

  // Branch on `code`, never on `message` (CONTRACTS.md) -- codes
  // don't get reworded, messages do. NOT_AUTHENTICATED is handled as its
  // own 401 branch, same as pollOnce() already does. A short is limited by
  // free cash exactly like a long -- both reserve their margin
  // (CONTRACTS.md) -- so there is one "not enough" error for both.
  const TRADE_ERROR_MESSAGES = {
    INSUFFICIENT_FUNDS: "Not enough free cash for this trade.",
    INVALID_BODY: "Enter a valid quantity.",
    LIVE_PRICE_UNAVAILABLE: "Price unavailable right now — try again in a moment.",
    NOT_FOUND: "This demo only trades BTC/USD."
  };

  // candleSeconds defaults to 60 (today's only value) so a redraw
  // triggered before the first poll answers doesn't divide by zero.
  let candles = [];            // [{start, open, high, low, close, live, forming}]
  let candleSeconds = 60;

  // Drag-to-pan through the chart's own session history. The backend's getLiveChart
  // window is a hard 30-minute rolling cache held in memory (CONTRACTS.md) -- a candle
  // older than that is gone server-side, not just unsent, so no request could ever bring
  // it back. What CAN go further back is whatever THIS page has already been handed
  // across its own polls: candleHistory keeps every distinct candle this tab has ever
  // received (keyed by its real start, so a still-forming candle keeps getting
  // overwritten in place rather than duplicated), for as long as the tab stays open.
  let candleHistory = new Map(); // startMs -> candle
  let isLive = true;         // true: the chart auto-follows the most recent
                              // 30-candle window.
                              // false: panned away -- the view stays pinned
                              // to viewEndMs regardless of new polls
                              // arriving, until dragged back or "Jump to
                              // live" is clicked.
  let viewEndMs = null;      // only meaningful while !isLive: the fixed
                              // right-hand edge (ms) of the panned view.
  let isDragging = false;
  let dragStartX = null;
  let dragStartViewEnd = null;
  let lastDisplayedPrice = null; // previous poll's price, for the up/down/flat tick
  let pollHandle = null;
  let active = false;         // the poll loop is running
  let started = false;        // polling has been started at least once
  let resizeHandle = null;
  let hoverStartMs = null;   // start (ms) of the candle under the hover
                              // crosshair, or null when the mouse isn't
                              // over the chart. Re-resolved against the
                              // fresh candle array on every redraw (poll or
                              // resize) so the crosshair tracks the same
                              // real candle instead of a stale index if the
                              // array shifts underneath it.
  let hoverPixelY = null;    // last known cursor Y inside the chart
                              // container's own pixel space -- the
                              // horizontal crosshair line follows the
                              // actual cursor position (whatever price is
                              // under it), while the vertical line snaps
                              // to a real candle.
  let chartLayout = null;    // pixel<->data mapping for whatever chart is
                              // on screen right now, refreshed by every
                              // drawChart() call -- hover math reads this
                              // instead of keeping a separate copy of the
                              // same numbers.
  let tooltipEl = null;      // the hover tooltip's DOM node, recreated on
                              // every render pass (see renderCrosshair()).
  let lastAccount = null;    // last account block rendered -- its free
                              // `cash` is what MAX / quick-fill size against.
                              // Null only before the first poll answers: the
                              // block itself is never null for a signed-in
                              // user (CONTRACTS.md).
  let lastPrice = null;      // last known BTC/USD price, used to convert a
                              // USD amount typed in the order panel into the
                              // BTC quantity the backend actually accepts
                              // (POST /api/trades never takes a price --
                              // CONTRACTS.md) and for MAX/quick-fill maths.
  let orderDirection = "LONG"; // "LONG" | "SHORT" -- which direction segment
                                // is selected in the place-order panel
  let orderUnit = "USD";     // "USD" | "QTY" -- which unit segment is
                              // selected in the place-order panel
  let closedTrades = [];     // the history, closed trades newest first
  let dialogTradeId = null;  // the trade #dt-trade-dialog is showing
  let positionRows = new Map(); // open trade id -> {row, pnl, close} -- see
                                 // renderOpenTrades() for why rows are kept
                                 // rather than rebuilt every poll.

  function show(el) { el.hidden = false; }
  function hide(el) { el.hidden = true; }

  function formatPrice(price) {
    return "$" + Number(price).toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  // Quantities are stored to 8 decimal places (CONTRACTS.md) -- trims
  // trailing zeros so "0.00250000" reads as "0.0025", never rounds.
  function formatQuantity(qty) {
    let str = Number(qty).toFixed(8);
    str = str.replace(/0+$/, "").replace(/\.$/, "");
    return str === "" ? "0" : str;
  }

  // Compares this poll's price to the previous one -- "since the last
  // second", literally the last tick, independent of whether this tick
  // also redrew the chart.
  function renderPrice(price) {
    priceEl.textContent = formatPrice(price);

    let direction = "flat";
    if (lastDisplayedPrice !== null) {
      if (price > lastDisplayedPrice) direction = "up";
      else if (price < lastDisplayedPrice) direction = "down";
    }
    changeIconEl.textContent = CHANGE_ICONS[direction];
    changeIconEl.classList.remove("is-positive", "is-negative", "is-flat");
    changeIconEl.classList.add(
      direction === "up" ? "is-positive" : direction === "down" ? "is-negative" : "is-flat"
    );
    lastDisplayedPrice = price;
  }

  // Rides the same once-a-second getLiveChart poll as the chart -- no second polling
  // loop, so every P&L on the page is valued against the exact same price snapshot the
  // candles are drawn from (CONTRACTS.md). Also called straight from the open/close
  // responses, which carry the same block, so the balance never lags a trade by a poll.
  // Every figure is read off the block as sent. `equity`, `unrealisedPnl` and an open
  // trade's `pnl` are null when trades are open but there is no live price: null reads
  // "—", never 0.
  function renderAccount(account) {
    lastAccount = account;

    topbarTotalEl.textContent = account.equity === null ? "—" : formatPrice(account.equity);
    topbarCashEl.textContent = formatPrice(account.cash);
    topbarMarginEl.textContent = formatPrice(account.margin);
    renderSignedUsd(topbarPnlEl,
      account.unrealisedPnl === null ? null : account.realisedPnl + account.unrealisedPnl);
    availableValueEl.textContent = formatPrice(account.cash);

    renderOpenTrades(account.openTrades);
  }

  // Signing out: back to the "—" the page loads with, until the next
  // sign-in's first poll answers. Neither "No open positions" nor a
  // number is shown before the server has said so.
  function clearAccount() {
    lastAccount = null;
    [topbarTotalEl, topbarCashEl, topbarMarginEl, availableValueEl].forEach(function (el) {
      el.textContent = "—";
    });
    renderSignedUsd(topbarPnlEl, null);
    positionRows.forEach(function (entry) { entry.row.remove(); });
    positionRows = new Map();
    hide(positionsList);
    hide(positionsEmpty);
  }

  // Coloured by the amount as SHOWN (whole cents): a trade up a fraction of
  // a cent reads as a flat $0.00, not a green "+$0.00". Null (no live
  // price) reads "—" with no colour at all.
  function renderSignedUsd(el, amount, percent) {
    el.classList.remove("is-positive", "is-negative", "is-flat");
    if (amount === null) {
      el.textContent = "—";
      return;
    }
    const cents = Math.round(amount * 100);
    el.classList.add(cents > 0 ? "is-positive" : cents < 0 ? "is-negative" : "is-flat");
    el.textContent = formatSignedUsd(amount) +
      (percent === null || percent === undefined ? "" : " (" + formatSignedPercent(percent) + ")");
  }

  // "Long" / "Short" in rows, deliberately NOT green/red: colour on this
  // page means money made or lost, and a short isn't a loss -- in a
  // falling market it is the winning side.
  function directionLabel(direction) {
    return direction === "LONG" ? "Long" : "Short";
  }

  // One row per open trade, oldest first (CONTRACTS.md). Rows are kept and
  // updated in place rather than rebuilt: this runs every second, and a
  // rebuild would swap a Close button out between the press and release of
  // a click -- losing the click -- and would drop the disabled state of a
  // close still in flight. A row goes only once its trade is no longer open.
  function renderOpenTrades(openTrades) {
    const openIds = new Set(openTrades.map(function (t) { return t.id; }));
    positionRows.forEach(function (entry, id) {
      if (!openIds.has(id)) {
        entry.row.remove();
        positionRows.delete(id);
      }
    });

    openTrades.forEach(function (t) {
      let entry = positionRows.get(t.id);
      if (!entry) {
        entry = createPositionRow(t);
        positionRows.set(t.id, entry);
        positionsList.appendChild(entry.row); // newest is always last
      }
      renderSignedUsd(entry.pnl, t.pnl, t.pnlPercent);
    });

    positionsList.hidden = positionRows.size === 0;
    positionsEmpty.hidden = positionRows.size > 0;
    fitPositions();
  }

  // At most POSITIONS_VISIBLE rows show at once; the rest scroll inside the
  // list, so a fourth open trade doesn't push Place order further down the
  // sidebar. Measured rather than a fixed CSS height because a row wraps
  // onto more lines in a narrow sidebar. Never cleared first: this runs on
  // every poll, and clearing the height would throw away the scroll position.
  const POSITIONS_VISIBLE = 3;
  function fitPositions() {
    const last = positionsList.children[POSITIONS_VISIBLE - 1];
    const overflowing = positionsList.children.length > POSITIONS_VISIBLE && last.offsetHeight > 0;
    positionsList.style.maxHeight = overflowing ? (last.offsetTop + last.offsetHeight) + "px" : "";
  }
  window.addEventListener("resize", fitPositions);

  // Everything but the P&L is fixed for the life of an open trade, so it
  // is written once, here.
  function createPositionRow(t) {
    const row = document.createElement("li");
    row.className = "dt-position-row";

    const direction = document.createElement("span");
    direction.className = "dt-position-direction";
    direction.textContent = directionLabel(t.direction);

    const qty = document.createElement("span");
    qty.className = "dt-position-qty";
    qty.textContent = formatQuantity(t.quantity) + " BTC";

    const entryPrice = document.createElement("span");
    entryPrice.className = "dt-position-entry";
    entryPrice.textContent = "@ " + formatPrice(t.entryPrice);

    const pnl = document.createElement("span");
    pnl.className = "dt-position-pnl";

    const close = document.createElement("button");
    close.type = "button";
    close.className = "dt-position-close";
    close.textContent = "Close";
    close.setAttribute("aria-label",
      "Close " + directionLabel(t.direction).toLowerCase() + " " + formatQuantity(t.quantity) + " BTC");
    close.addEventListener("click", function () { closeTrade(t.id, close); });

    // Two lines -- what it is and its Close button; where it opened and
    // how it stands -- each free to wrap in a narrow sidebar rather than
    // overlap.
    const head = document.createElement("div");
    head.className = "dt-position-line";
    head.appendChild(direction);
    head.appendChild(qty);
    head.appendChild(close);

    const body = document.createElement("div");
    body.className = "dt-position-line";
    body.appendChild(entryPrice);
    body.appendChild(pnl);

    row.appendChild(head);
    row.appendChild(body);
    return { row: row, pnl: pnl, close: close };
  }

  function updateDirectionUI() {
    const isLong = orderDirection === "LONG";
    directionLongButton.classList.toggle("is-active", isLong);
    directionShortButton.classList.toggle("is-active", !isLong);
    submitButton.textContent = (isLong ? "Long" : "Short") + " BTC";
    submitButton.classList.toggle("dt-long-button", isLong);
    submitButton.classList.toggle("dt-short-button", !isLong);
  }

  function updateUnitUI() {
    const isUsd = orderUnit === "USD";
    unitUsdButton.classList.toggle("is-active", isUsd);
    unitQtyButton.classList.toggle("is-active", !isUsd);
    quantityLabel.textContent = isUsd ? "Amount (USD)" : "Amount (BTC)";
    quantityInput.placeholder = isUsd ? "$0.00" : "0.00 BTC";
  }

  // MAX / 25% / 50% / 75% / 100% all share this: how much the free cash
  // allows in the current unit, before applying the percentage. The same
  // for a long and a short -- both reserve their margin out of cash
  // (CONTRACTS.md). Returns null only when a BTC amount would
  // need a price that hasn't arrived yet.
  function computeMaxAmount() {
    const cash = lastAccount ? lastAccount.cash : 0;
    if (orderUnit === "USD") return cash;
    if (lastPrice === null || lastPrice <= 0) return null;
    return cash / lastPrice;
  }

  function applyPercent(pct) {
    const max = computeMaxAmount();
    if (max === null) {
      tradeError.textContent = "Price unavailable right now — try again in a moment.";
      show(tradeError);
      return;
    }
    hide(tradeError);
    const amount = max * (pct / 100);
    quantityInput.value = orderUnit === "USD" ? amount.toFixed(2) : formatQuantity(amount);
    updateOrderPreview();
  }

  // Live Quantity / Price / Margin preview under the quick-fill row. Mirrors
  // exactly what openTrade() will send: for a USD amount it floors to 8
  // decimals the same way parseQuantityInput() does, so the preview never
  // promises a size the backend would then reject (CONTRACTS.md). The same
  // for either direction -- a long and a short of the same size reserve the
  // same margin. The block is always shown. Price always follows
  // the live price; Quantity and Margin show a dimmed 0.00 placeholder until
  // a usable amount is typed.
  function updateOrderPreview() {
    const hasPrice = lastPrice !== null && lastPrice > 0;
    previewPriceEl.textContent = hasPrice ? formatPrice(lastPrice) : "—";

    const raw = quantityInput.value.trim();
    const num = Number(raw);
    let quantity = 0;
    if (raw !== "" && Number.isFinite(num) && num > 0 && hasPrice) {
      quantity = orderUnit === "QTY" ? num : Math.floor((num / lastPrice) * 1e8) / 1e8;
    }

    if (quantity > 0) {
      previewQuantityEl.textContent = formatQuantity(quantity) + " BTC";
      previewMarginEl.textContent = formatPrice(quantity * lastPrice);
    } else {
      previewQuantityEl.textContent = "0.00 BTC";
      previewMarginEl.textContent = "$0.00";
    }
    previewQuantityEl.classList.toggle("is-placeholder", quantity <= 0);
    previewMarginEl.classList.toggle("is-placeholder", quantity <= 0);
  }

  // ---- Data ---------------------------------------------------------------

  async function pollOnce() {
    let response;
    try {
      response = await fetch("/api/getLiveChart?symbol=" + encodeURIComponent(SYMBOL));
    } catch (networkErr) {
      staleNotice.textContent = STALE_MESSAGE;
      show(staleNotice);
      return;
    }

    if (response.status === 401) {
      // Session expired mid-page. Nothing here polls /api/me, so just stop
      // -- the next login fires a fresh "easytrading:authchange" and the
      // gate branch below restarts everything from a clean state.
      stopPolling();
      show(gate);
      hide(chartSection);
      return;
    }

    if (!response.ok) {
      // Covers 404 and anything else unexpected: same visible treatment as
      // a network failure, keep the last drawn chart on screen.
      staleNotice.textContent = STALE_MESSAGE;
      show(staleNotice);
      return;
    }

    let body;
    try {
      body = await response.json();
    } catch (parseErr) {
      staleNotice.textContent = STALE_MESSAGE;
      show(staleNotice);
      return;
    }

    candles = body.candles || [];
    candleSeconds = body.candleSeconds || candleSeconds;
    candles.forEach(function (c) {
      candleHistory.set(Date.parse(c.start), c);
    });

    if (candles.length === 0) {
      // Empty candles is a normal 200 (CONTRACTS.md) -- history unavailable
      // right now, not an error. price/priceAt are null in this case, so
      // the price readout below is simply left as whatever it last showed.
      historyNotice.textContent = "No recent history for BTC/USD yet — showing live prices only.";
      show(historyNotice);
    } else {
      hide(historyNotice);
    }

    // `outdated` only toggles this notice. The chart redraws below
    // either way, from whatever candles this poll returned.
    if (body.outdated) {
      staleNotice.textContent = STALE_MESSAGE;
      show(staleNotice);
    } else {
      hide(staleNotice);
    }

    if (typeof body.price === "number") {
      lastPrice = body.price;
      renderPrice(body.price);
      updateOrderPreview();
    }
    renderAccount(body.account); // never null for a signed-in user (CONTRACTS.md)

    drawChart();
  }

  // ---- Poll loop ------------------------------------------------------

  function loop() {
    if (!active) return;
    pollOnce().finally(function () {
      if (active) {
        pollHandle = window.setTimeout(loop, POLL_MS);
      }
    });
  }

  function startPolling() {
    if (active) return;
    active = true;
    loop();
  }

  function stopPolling() {
    active = false;
    if (pollHandle !== null) {
      window.clearTimeout(pollHandle);
      pollHandle = null;
    }
  }

  document.addEventListener("visibilitychange", function () {
    if (!started) return;
    if (document.visibilityState === "hidden") {
      stopPolling();
    } else {
      startPolling(); // immediate poll on return, not a stale wait
    }
  });

  // ---- Chart: hand-built inline SVG candlesticks (same house style as
  // search.js's candlestick renderer -- document.createElementNS, redrawn
  // wholesale on every poll and on resize, wick + body coloured by
  // up/down). ----------------------------------

  // Shared pixel<->data mapping for whatever chart is currently drawn --
  // used both by drawChart() itself and by the hover crosshair below, so
  // there is exactly one place doing this maths rather than two copies
  // that could quietly drift apart.
  function layoutX(t) {
    return chartLayout.padLeft + ((t - chartLayout.tStart) / chartLayout.spanMs) * chartLayout.plotWidth;
  }
  function layoutY(price) {
    return chartLayout.padTop + (1 - (price - chartLayout.min) / ((chartLayout.max - chartLayout.min) || 1)) * chartLayout.plotHeight;
  }
  function layoutInvertY(pixelY) {
    return chartLayout.max - ((pixelY - chartLayout.padTop) / chartLayout.plotHeight) * (chartLayout.max - chartLayout.min);
  }

  function drawChart() {
    chartContainer.innerHTML = "";
    if (candleHistory.size === 0) {
      chartLayout = null;
      hoverStartMs = null;
      hoverPixelY = null;
      tooltipEl = null;
      return;
    }

    const sorted = Array.from(candleHistory.values()).sort(function (a, b) {
      return Date.parse(a.start) - Date.parse(b.start);
    });

    const width = chartContainer.clientWidth || 600;
    const height = chartContainer.clientHeight || 260;
    const padLeft = 58;
    const padRight = 58; // wide enough for the hover crosshair's price tag
                          // (renderCrosshair() below) -- matches padLeft so
                          // the axis reads symmetrically.
    const padTop = 14;
    const padBottom = 14;
    const plotWidth = width - padLeft - padRight;
    const plotHeight = height - padTop - padBottom;

    // Positioned by real elapsed time, not array index -- see point 5 in
    // the file header: this is what lets the hover crosshair and the pan
    // below map a pixel position back to an honest timestamp. The visible
    // window is always VIEW_CANDLES wide in time; isLive tracks the most
    // recent one, a pan pins it to viewEndMs instead (see the drag
    // handlers below this function).
    const bucketMs = candleSeconds * 1000;
    const earliestStart = Date.parse(sorted[0].start);
    const liveEnd = Date.parse(sorted[sorted.length - 1].start) + bucketMs;
    const spanMs = candleSeconds * 1000 * VIEW_CANDLES;

    let tEnd;
    if (isLive) {
      tEnd = liveEnd;
    } else {
      tEnd = Math.min(viewEndMs, liveEnd);
      if (tEnd >= liveEnd - bucketMs / 2) {
        // Dragged (or already was) back to the live edge -- resume
        // auto-following instead of sitting one candle behind forever.
        isLive = true;
        viewEndMs = null;
        tEnd = liveEnd;
        hide(jumpLiveButton);
      }
    }
    // Always show at least the first candle this tab has ever seen -- the
    // real, honest limit on "how far back", per candleHistory's comment
    // above.
    tEnd = Math.max(tEnd, earliestStart + bucketMs);
    const tStart = tEnd - spanMs;

    const visibleCandles = sorted.filter(function (c) {
      const s = Date.parse(c.start);
      return s + bucketMs > tStart && s < tEnd;
    });

    let min = visibleCandles.length > 0 ? visibleCandles[0].low : 0;
    let max = visibleCandles.length > 0 ? visibleCandles[0].high : 1;
    visibleCandles.forEach(function (c) {
      if (c.low < min) min = c.low;
      if (c.high > max) max = c.high;
    });
    if (min === max) { min -= 1; max += 1; }
    const pricePad = (max - min) * 0.08;
    min -= pricePad;
    max += pricePad;

    // Stored so the hover crosshair and the drag-to-pan handlers (both
    // below) can convert cursor pixels to a real candle/price/time without
    // keeping their own separate copy of this layout.
    chartLayout = {
      width: width, height: height,
      padLeft: padLeft, padRight: padRight, padTop: padTop, padBottom: padBottom,
      plotWidth: plotWidth, plotHeight: plotHeight,
      min: min, max: max,
      tStart: tStart, spanMs: spanMs, bucketMs: bucketMs,
      earliestStart: earliestStart, liveEnd: liveEnd,
      candles: visibleCandles
    };

    const svgNS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("viewBox", "0 0 " + width + " " + height);
    svg.setAttribute("width", "100%");
    svg.setAttribute("height", "100%");
    svg.setAttribute("aria-label", "BTC/USD candlestick chart");

    // Gridlines + price labels.
    const rows = 3;
    for (let i = 0; i <= rows; i++) {
      const price = min + ((max - min) * i) / rows;
      const gy = layoutY(price);

      const line = document.createElementNS(svgNS, "line");
      line.setAttribute("x1", padLeft);
      line.setAttribute("x2", width - padRight);
      line.setAttribute("y1", gy);
      line.setAttribute("y2", gy);
      line.setAttribute("style", "stroke: var(--border); stroke-width: 1;");
      svg.appendChild(line);

      const label = document.createElementNS(svgNS, "text");
      label.setAttribute("x", 4);
      label.setAttribute("y", gy + 3);
      label.setAttribute("style", "fill: var(--text-tertiary); font-size: 9px;");
      label.textContent = formatPrice(price);
      svg.appendChild(label);
    }

    // Faint divider marking the history -> live boundary: the first
    // visible candle whose `live` flips from false to true. Naturally
    // absent once every visible candle is live, or while
    // panned deep enough into history that the boundary itself is off to
    // the left of the current view.
    const dividerIndex = visibleCandles.findIndex(function (c, i) {
      return i > 0 && c.live && !visibleCandles[i - 1].live;
    });
    if (dividerIndex > 0) {
      const dx = layoutX(Date.parse(visibleCandles[dividerIndex].start));
      const dline = document.createElementNS(svgNS, "line");
      dline.setAttribute("x1", dx);
      dline.setAttribute("x2", dx);
      dline.setAttribute("y1", padTop);
      dline.setAttribute("y2", height - padBottom);
      dline.setAttribute("style", "stroke: var(--border-strong); stroke-width: 1; stroke-dasharray: 3 3;");
      svg.appendChild(dline);
    }

    // Each candle: a wick (high-low) plus a body (open-close), coloured the
    // same up/down green/red as the rest of the app, positioned by its real
    // `start` time. Body width is proportional to the fixed
    // VIEW_CANDLES-wide window but floored so a candle never shrinks to
    // nothing. The
    // still-forming candle (at most one, always last, only ever visible
    // while isLive) is drawn slightly translucent so it visibly reads as
    // "still moving" rather than sealed.
    const bodyWidth = Math.max(3, (bucketMs / spanMs) * plotWidth * 0.6);
    visibleCandles.forEach(function (c) {
      const cx = layoutX(Date.parse(c.start) + bucketMs / 2);
      const isUp = c.close >= c.open;
      const colorVar = isUp ? "var(--positive)" : "var(--negative)";
      const opacity = c.forming ? " opacity: 0.75;" : "";

      const wick = document.createElementNS(svgNS, "line");
      wick.setAttribute("x1", cx);
      wick.setAttribute("x2", cx);
      wick.setAttribute("y1", layoutY(c.high));
      wick.setAttribute("y2", layoutY(c.low));
      wick.setAttribute("style", "stroke: " + colorVar + "; stroke-width: 1.5;" + opacity);
      svg.appendChild(wick);

      const openY = layoutY(c.open);
      const closeY = layoutY(c.close);
      const body = document.createElementNS(svgNS, "rect");
      body.setAttribute("x", cx - bodyWidth / 2);
      body.setAttribute("y", Math.min(openY, closeY));
      body.setAttribute("width", bodyWidth);
      body.setAttribute("height", Math.max(1.5, Math.abs(closeY - openY)));
      body.setAttribute("rx", Math.min(2, bodyWidth / 4));
      body.setAttribute("style", "fill: " + colorVar + ";" + opacity);
      svg.appendChild(body);
    });

    chartContainer.appendChild(svg);

    // The innerHTML reset above just wiped any crosshair drawn on the
    // previous frame along with everything else -- redraw it on top of the
    // fresh chart if the mouse is still sitting over the same candle (see
    // renderCrosshair() below).
    if (hoverStartMs !== null) {
      renderCrosshair();
    }
  }

  // ---- Hover crosshair ----------------------------------
  // Vertical line snaps to the real candle nearest the cursor (an exact
  // minute, matching the tooltip below); horizontal line follows the
  // cursor's actual Y position (whatever price is under it). The tooltip
  // shows the snapped candle's real `start` (UTC, same "already zoned,
  // don't append Z" rule as everywhere else this field is used) plus its
  // open/high/low/close.

  function findNearestCandle(pixelX) {
    let nearest = chartLayout.candles[0];
    let nearestDist = Infinity;
    chartLayout.candles.forEach(function (c) {
      const cx = layoutX(Date.parse(c.start) + chartLayout.bucketMs / 2);
      const dist = Math.abs(cx - pixelX);
      if (dist < nearestDist) {
        nearestDist = dist;
        nearest = c;
      }
    });
    return nearest;
  }

  function removeCrosshairElements() {
    const svg = chartContainer.querySelector("svg");
    if (svg) {
      svg.querySelectorAll(".dt-crosshair-el").forEach(function (el) { el.remove(); });
    }
    if (tooltipEl && tooltipEl.parentNode) {
      tooltipEl.parentNode.removeChild(tooltipEl);
    }
    tooltipEl = null;
  }

  function renderCrosshair() {
    removeCrosshairElements();
    if (!chartLayout || hoverStartMs === null || hoverPixelY === null) return;

    const candle = chartLayout.candles.find(function (c) {
      return Date.parse(c.start) === hoverStartMs;
    });
    if (!candle) {
      // The hovered minute fell out of the window this poll (a long
      // outage can do this) -- nothing honest to show any more.
      hoverStartMs = null;
      hoverPixelY = null;
      return;
    }

    const svg = chartContainer.querySelector("svg");
    if (!svg) return;
    const svgNS = "http://www.w3.org/2000/svg";

    const cx = layoutX(Date.parse(candle.start) + chartLayout.bucketMs / 2);
    const cy = Math.min(
      chartLayout.height - chartLayout.padBottom,
      Math.max(chartLayout.padTop, hoverPixelY)
    );

    const vLine = document.createElementNS(svgNS, "line");
    vLine.setAttribute("class", "dt-crosshair-el");
    vLine.setAttribute("x1", cx);
    vLine.setAttribute("x2", cx);
    vLine.setAttribute("y1", chartLayout.padTop);
    vLine.setAttribute("y2", chartLayout.height - chartLayout.padBottom);
    vLine.setAttribute("style", "stroke: var(--text-tertiary); stroke-width: 1; stroke-dasharray: 4 3;");
    svg.appendChild(vLine);

    const hLine = document.createElementNS(svgNS, "line");
    hLine.setAttribute("class", "dt-crosshair-el");
    hLine.setAttribute("x1", chartLayout.padLeft);
    hLine.setAttribute("x2", chartLayout.width - chartLayout.padRight);
    hLine.setAttribute("y1", cy);
    hLine.setAttribute("y2", cy);
    hLine.setAttribute("style", "stroke: var(--text-tertiary); stroke-width: 1; stroke-dasharray: 4 3;");
    svg.appendChild(hLine);

    // Price tag at the horizontal line's right end -- boxed (rect behind
    // the text) rather than bare numbers floating on top of the line.
    // Sized/positioned to sit inside padRight, which drawChart() above
    // sizes specifically to fit this.
    const tagHeight = 16;
    const tagWidth = chartLayout.padRight - 6;
    const tagX = chartLayout.width - chartLayout.padRight + 2;
    const tagY = Math.min(
      chartLayout.height - chartLayout.padBottom - tagHeight,
      Math.max(chartLayout.padTop, cy - tagHeight / 2)
    );

    const tagRect = document.createElementNS(svgNS, "rect");
    tagRect.setAttribute("class", "dt-crosshair-el");
    tagRect.setAttribute("x", tagX);
    tagRect.setAttribute("y", tagY);
    tagRect.setAttribute("width", tagWidth);
    tagRect.setAttribute("height", tagHeight);
    tagRect.setAttribute("rx", 3);
    tagRect.setAttribute("style", "fill: var(--surface); stroke: var(--border-strong); stroke-width: 1;");
    svg.appendChild(tagRect);

    const tagText = document.createElementNS(svgNS, "text");
    tagText.setAttribute("class", "dt-crosshair-el");
    tagText.setAttribute("x", tagX + tagWidth / 2);
    tagText.setAttribute("y", tagY + tagHeight / 2 + 3);
    tagText.setAttribute("text-anchor", "middle");
    tagText.setAttribute("style", "fill: var(--text-primary); font-size: 9px; font-weight: 700;");
    tagText.textContent = formatPrice(layoutInvertY(cy));
    svg.appendChild(tagText);

    tooltipEl = document.createElement("div");
    tooltipEl.className = "dt-chart-tooltip";

    const dateRow = document.createElement("p");
    dateRow.className = "dt-chart-tooltip-date";
    dateRow.textContent = new Date(candle.start).toLocaleString(undefined, {
      day: "numeric", month: "short", year: "numeric",
      hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "UTC"
    }) + " UTC";
    tooltipEl.appendChild(dateRow);

    [
      ["Open", candle.open],
      ["High", candle.high],
      ["Low", candle.low],
      ["Close", candle.close]
    ].forEach(function (pair) {
      const row = document.createElement("p");
      row.className = "dt-chart-tooltip-row";
      const label = document.createElement("span");
      label.textContent = pair[0];
      const value = document.createElement("span");
      value.textContent = formatPrice(pair[1]);
      row.appendChild(label);
      row.appendChild(value);
      tooltipEl.appendChild(row);
    });

    // Keeps the box on-screen near the crosshair rather than centred on
    // it -- flips to the other side once the cursor nears that edge of the
    // chart, same idea as a native browser tooltip.
    const tooltipWidth = 190;
    let left = cx + 12;
    if (left + tooltipWidth > chartLayout.width - chartLayout.padRight) {
      left = cx - tooltipWidth - 12;
    }
    tooltipEl.style.left = left + "px";
    tooltipEl.style.top = (chartLayout.padTop + 4) + "px";
    chartContainer.appendChild(tooltipEl);
  }

  // ---- Drag-to-pan --------------------------------------
  // Dragging the chart shifts the fixed VIEW_CANDLES-wide window backward
  // or forward through candleHistory; releasing leaves it pinned there
  // (isLive = false) until the person drags back to the live edge or
  // clicks "Jump to live". The move/up listeners live on `document`
  // (added on mousedown, removed on mouseup) rather than on the chart
  // container itself, so a fast drag that briefly leaves the container's
  // pixel bounds keeps tracking the cursor instead of getting stuck.

  function handleDragMove(event) {
    if (!isDragging || !chartLayout) return;
    const deltaPixels = event.clientX - dragStartX;
    const deltaMs = -(deltaPixels / chartLayout.plotWidth) * chartLayout.spanMs;
    const proposed = dragStartViewEnd + deltaMs;
    const clamped = Math.min(
      chartLayout.liveEnd,
      Math.max(chartLayout.earliestStart + chartLayout.bucketMs, proposed)
    );
    isLive = false;
    viewEndMs = clamped;
    drawChart(); // may itself snap isLive back to true if dragged to the
                 // live edge -- checked below rather than assumed.
    if (!isLive) {
      show(jumpLiveButton);
    }
  }

  function endDrag() {
    if (!isDragging) return;
    isDragging = false;
    chartContainer.classList.remove("is-panning");
    document.removeEventListener("mousemove", handleDragMove);
    document.removeEventListener("mouseup", endDrag);
  }

  chartContainer.addEventListener("mousedown", function (event) {
    if (!chartLayout) return;
    isDragging = true;
    dragStartX = event.clientX;
    dragStartViewEnd = isLive ? chartLayout.liveEnd : viewEndMs;
    chartContainer.classList.add("is-panning");
    hoverStartMs = null;
    hoverPixelY = null;
    removeCrosshairElements();
    document.addEventListener("mousemove", handleDragMove);
    document.addEventListener("mouseup", endDrag);
  });

  chartContainer.addEventListener("mousemove", function (event) {
    if (isDragging) return; // handled by handleDragMove above instead
    if (!chartLayout || chartLayout.candles.length === 0) return;
    const rect = chartContainer.getBoundingClientRect();
    const pixelX = event.clientX - rect.left;
    const pixelY = event.clientY - rect.top;

    if (
      pixelX < chartLayout.padLeft || pixelX > chartLayout.width - chartLayout.padRight ||
      pixelY < chartLayout.padTop || pixelY > chartLayout.height - chartLayout.padBottom
    ) {
      hoverStartMs = null;
      hoverPixelY = null;
      removeCrosshairElements();
      return;
    }

    hoverStartMs = Date.parse(findNearestCandle(pixelX).start);
    hoverPixelY = pixelY;
    renderCrosshair();
  });

  chartContainer.addEventListener("mouseleave", function () {
    hoverStartMs = null;
    hoverPixelY = null;
    removeCrosshairElements();
  });

  jumpLiveButton.addEventListener("click", function () {
    isLive = true;
    viewEndMs = null;
    hide(jumpLiveButton);
    drawChart();
  });

  window.addEventListener("resize", function () {
    window.clearTimeout(resizeHandle);
    resizeHandle = window.setTimeout(drawChart, 100);
  });

  // ---- Trading --------------------
  // Neither POST ever carries a price -- the server opens and closes at its
  // own last known price and hands back what it actually used, which can
  // differ slightly from whatever was on screen when the button was
  // pressed. None of the error paths below clear the quantity field or
  // touch the chart -- only a successful trade does.

  function setTradeButtonsDisabled(disabled) {
    submitButton.disabled = disabled;
    maxButton.disabled = disabled;
    quickfillButtons.forEach(function (btn) { btn.disabled = disabled; });
  }

  // Courtesy only, not a guarantee -- the backend checks everything again
  // (CONTRACTS.md). POST /api/trades only ever accepts a BTC quantity, so a
  // USD amount typed here is converted at the last known price before it's
  // sent -- the same conversion the MAX/quick-fill buttons already do.
  function parseQuantityInput() {
    const raw = quantityInput.value.trim();
    if (raw === "") return { error: "Enter an amount." };

    const num = Number(raw);
    if (!Number.isFinite(num)) return { error: "Enter a valid number." };
    if (num <= 0) return { error: "Amount must be greater than zero." };

    if (orderUnit === "QTY") {
      const dot = raw.indexOf(".");
      if (dot !== -1 && raw.length - dot - 1 > 8) {
        return { error: "Quantity accepts at most 8 decimal places." };
      }
      return { value: num };
    }

    if (lastPrice === null || lastPrice <= 0) {
      return { error: "Price unavailable right now — try again in a moment." };
    }

    // The backend REJECTS (never rounds) a quantity with more than 8
    // decimal places (backend/CONTRACTS.md) -- a raw division like
    // 10 / 86140.01 comes out to 17+ significant digits. Floor (never
    // round up) so the order never ends up costing more than the USD
    // amount actually typed in.
    const floored = Math.floor((num / lastPrice) * 1e8) / 1e8;
    if (floored <= 0) {
      return { error: "Amount too small to trade at the current price." };
    }
    return { value: floored };
  }

  // Opening and closing each clear the other's message too, so the sidebar
  // never shows a confirmation that no longer describes the latest action.
  function hideTradeMessages() {
    hide(tradeError);
    hide(tradeConfirm);
    hide(closeError);
    hide(closeConfirm);
  }

  // Same convention as pollOnce(): stop everything and let the gate take
  // over -- a fresh login fires its own authchange.
  function showSignedOutGate() {
    stopPolling();
    show(gate);
    hide(chartSection);
    hide(tradingSection);
  }

  // Tells js/journal.js the trade list changed, so its "link
  // one of your trades" picker offers a new trade straight away and a
  // linked trade's tag shows its result once it closes. An event rather
  // than a direct call, same seam as auth.js's easytrading:authchange --
  // this file doesn't need to know the journal exists.
  function announceTradesChanged() {
    document.dispatchEvent(new CustomEvent("easytrading:tradeschanged"));
  }

  async function openTrade(direction) {
    hideTradeMessages();

    const parsed = parseQuantityInput();
    if (parsed.error) {
      tradeError.textContent = parsed.error;
      show(tradeError);
      return;
    }

    setTradeButtonsDisabled(true);

    let response;
    try {
      response = await fetch("/api/trades", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ symbol: SYMBOL, direction: direction, quantity: parsed.value })
      });
    } catch (networkErr) {
      tradeError.textContent = "Couldn't reach the server — try again in a moment.";
      show(tradeError);
      setTradeButtonsDisabled(false);
      return;
    }

    if (response.status === 401) {
      setTradeButtonsDisabled(false);
      showSignedOutGate();
      return;
    }

    let body;
    try {
      body = await response.json();
    } catch (parseErr) {
      tradeError.textContent = "Something went wrong reading the server's response.";
      show(tradeError);
      setTradeButtonsDisabled(false);
      return;
    }

    if (!response.ok) {
      // Every one of these is normal (CONTRACTS.md) -- the form and the
      // chart both stay exactly as they were so the user can just adjust
      // and retry.
      tradeError.textContent = TRADE_ERROR_MESSAGES[body.code] || "Something went wrong — try again.";
      show(tradeError);
      setTradeButtonsDisabled(false);
      return;
    }

    // Success: update the strip straight from the response rather than
    // waiting for the next poll (CONTRACTS.md) -- the page shouldn't show a
    // stale balance for up to a second after a trade just opened. The
    // history lists closed trades only, so it has nothing new to show yet.
    renderAccount(body.account);
    const opened = body.trade;
    tradeConfirm.textContent = "Opened " + directionLabel(opened.direction).toLowerCase() + " " +
      formatQuantity(opened.quantity) + " BTC at " + formatPrice(opened.entryPrice) + ".";
    show(tradeConfirm);
    quantityInput.value = "";
    updateOrderPreview();
    setTradeButtonsDisabled(false);
    announceTradesChanged();
  }

  // Closes one open trade, whole (CONTRACTS.md). The row's button stays
  // disabled for the whole round trip, so a double click sends one
  // request. A second request that gets through anyway (another tab) is
  // answered 409 TRADE_ALREADY_CLOSED, which means "already done": the page
  // refreshes and shows no error. The row itself is removed by the account
  // block that comes back, never by hand here.
  async function closeTrade(id, button) {
    hideTradeMessages();
    button.disabled = true;

    let response;
    try {
      response = await fetch("/api/trades/" + encodeURIComponent(id) + "/close", { method: "POST" });
    } catch (networkErr) {
      closeError.textContent = "Couldn't reach the server — the trade is still open.";
      show(closeError);
      button.disabled = false;
      return;
    }

    if (response.status === 401) {
      button.disabled = false;
      showSignedOutGate();
      return;
    }

    let body;
    try {
      body = await response.json();
    } catch (parseErr) {
      closeError.textContent = "Something went wrong reading the server's response.";
      show(closeError);
      button.disabled = false;
      return;
    }

    if (response.status === 409 && body.code === "TRADE_ALREADY_CLOSED") {
      // The next poll drops the row; the history can show the trade now.
      loadTradeHistory();
      announceTradesChanged();
      return;
    }

    if (!response.ok) {
      closeError.textContent = body.code === "LIVE_PRICE_UNAVAILABLE"
        ? "Price unavailable right now — the trade is still open. Try again in a moment."
        : "Something went wrong — try again.";
      show(closeError);
      button.disabled = false;
      return;
    }

    renderAccount(body.account);
    const closed = body.trade;
    closeConfirm.textContent = "Closed " + directionLabel(closed.direction).toLowerCase() + " " +
      formatQuantity(closed.quantity) + " BTC at " + formatPrice(closed.exitPrice) + ": " +
      formatSignedUsd(closed.pnl) + ".";
    show(closeConfirm);
    loadTradeHistory();
    announceTradesChanged();
  }

  directionLongButton.addEventListener("click", function () {
    if (orderDirection === "LONG") return;
    orderDirection = "LONG";
    updateDirectionUI();
  });
  directionShortButton.addEventListener("click", function () {
    if (orderDirection === "SHORT") return;
    orderDirection = "SHORT";
    updateDirectionUI();
  });
  unitUsdButton.addEventListener("click", function () {
    if (orderUnit === "USD") return;
    orderUnit = "USD";
    quantityInput.value = "";
    updateUnitUI();
    updateOrderPreview();
  });
  unitQtyButton.addEventListener("click", function () {
    if (orderUnit === "QTY") return;
    orderUnit = "QTY";
    quantityInput.value = "";
    updateUnitUI();
    updateOrderPreview();
  });
  maxButton.addEventListener("click", function () { applyPercent(100); });
  quickfillButtons.forEach(function (btn) {
    btn.addEventListener("click", function () {
      applyPercent(Number(btn.dataset.pct));
    });
  });
  quantityInput.addEventListener("input", updateOrderPreview);
  submitButton.addEventListener("click", function () { openTrade(orderDirection); });

  updateDirectionUI();
  updateUnitUI();

  // Trade history needs no polling of its own (CONTRACTS.md) -- fetched
  // once the user is confirmed logged in, and again after each close.
  async function loadTradeHistory() {
    let response;
    try {
      response = await fetch("/api/trades?symbol=" + encodeURIComponent(SYMBOL));
    } catch (networkErr) {
      return; // best-effort -- leave whatever history is already shown
    }
    if (!response.ok) return; // includes 401; the poll loop already handles that case

    let body;
    try {
      body = await response.json();
    } catch (parseErr) {
      return;
    }
    renderTradeHistory(body.trades || []);
  }

  // ---- History height matches the sidebar ------------
  // Beside the sidebar, the history box grows or shrinks so its bottom lines
  // up with the bottom of the sidebar's content (Open positions + Place
  // order + Journal button), and the list shows as many WHOLE rows as fit in that
  // height -- more rows on a layout where the sidebar is taller, fewer where
  // it is shorter, never a half-cut row. Never fewer than HISTORY_MIN_ROWS.
  // Stacked (narrow) layout: nothing beside it to match, so CSS's fixed 4
  // rows apply.
  // With no open trades the sidebar is short. So the box also reaches
  // down to the bottom of the window (as it stands scrolled to the top), whichever
  // is taller -- the history fills the space instead of leaving it empty.
  const HISTORY_MIN_ROWS = 3;
  const WINDOW_GAP = 24; // px kept free under the box, its own top margin
  const historyBox = historyList.closest(".dt-history");
  const stackedLayout = window.matchMedia("(max-width: 760px)");
  let fitQueued = false;

  function queueFitHistory() {
    if (fitQueued) return;
    fitQueued = true;
    requestAnimationFrame(function () {
      fitQueued = false;
      fitHistory();
    });
  }

  function lastShownChild(parent) {
    for (let el = parent.lastElementChild; el; el = el.previousElementSibling) {
      if (!el.hidden && el.getBoundingClientRect().height > 0) return el;
    }
    return null;
  }

  function fitHistory() {
    historyBox.style.minHeight = "";
    historyList.style.maxHeight = "";
    if (chartSection.hidden || tradingSection.hidden || stackedLayout.matches) return;

    const last = lastShownChild(tradingSection);
    if (!last) return;
    // Measured INSIDE the sidebar, from its own top: the sidebar is
    // position:sticky, so its on-screen position shifts while scrolling,
    // but the distances within it don't. The sidebar and the chart column
    // start at the same grid row, so the chart column's top is where the
    // sidebar's content starts too.
    const sidebarContent = last.getBoundingClientRect().bottom - tradingSection.getBoundingClientRect().top;
    const historyTop = historyBox.getBoundingClientRect().top;
    const alongSidebar = chartSection.getBoundingClientRect().top + sidebarContent - historyTop;
    const toWindowBottom = window.innerHeight - (historyTop + window.scrollY) - WINDOW_GAP;
    const target = Math.max(alongSidebar, toWindowBottom);
    if (target <= 0) return;

    const firstRow = historyList.hidden ? null : historyList.firstElementChild;
    if (firstRow) {
      const gap = parseFloat(getComputedStyle(historyList).rowGap) || 0;
      const rowHeight = firstRow.getBoundingClientRect().height;
      const chrome = historyBox.getBoundingClientRect().height - historyList.getBoundingClientRect().height;
      const rows = Math.max(HISTORY_MIN_ROWS, Math.floor((target - chrome + gap) / (rowHeight + gap)));
      historyList.style.maxHeight = (rows * rowHeight + (rows - 1) * gap) + "px";
    }
    historyBox.style.minHeight = target + "px";
  }

  // Refit whenever something that sets the target height changes size:
  // the sidebar's parts (a trade message appearing, a position opening or
  // closing), the parts of the chart column above the history, or the
  // window. Deliberately NOT the history itself -- that is what gets
  // resized, and watching it would loop.
  if (typeof ResizeObserver === "function") {
    const watcher = new ResizeObserver(queueFitHistory);
    Array.prototype.forEach.call(tradingSection.children, function (el) { watcher.observe(el); });
    Array.prototype.forEach.call(chartSection.children, function (el) {
      if (el !== historyBox) watcher.observe(el);
    });
  }
  window.addEventListener("resize", queueFitHistory);

  // The history is the list of results, so it shows CLOSED trades
  // only -- an open trade has no result yet and is already listed, live,
  // under Open positions. GET /api/trades sends both, newest first by
  // openedAt (CONTRACTS.md); sorted here by closedAt instead, so the trade
  // just closed is always the top row, however long ago it was opened.
  // Kept in closedTrades so the filters and the trade dialog work from it
  // without another request.
  function renderTradeHistory(trades) {
    closedTrades = trades
      .filter(function (t) { return t.closedAt !== null; })
      .sort(function (a, b) { return Date.parse(b.closedAt) - Date.parse(a.closedAt); });
    renderHistoryRows();
  }

  function renderHistoryRows() {
    historyList.innerHTML = "";
    const direction = historyDirectionInput.value;
    const day = historyDateInput.value;
    const shown = closedTrades.filter(function (t) {
      return (!direction || t.direction === direction) && (!day || openOnDay(t, day));
    });

    if (shown.length === 0) {
      historyEmpty.textContent = closedTrades.length === 0
        ? "No closed trades yet."
        : "No trades match these filters.";
      show(historyEmpty);
      hide(historyList);
      queueFitHistory();
      return;
    }
    hide(historyEmpty);

    shown.forEach(function (t) {
      // The whole row opens the trade (openTradeDialog). A row rather than a
      // <button>: it is one line of the list's subgrid, which a button
      // inside it would break -- role, tabindex and Enter/Space make it
      // behave like one.
      const row = document.createElement("li");
      row.className = "dt-history-row";
      row.dataset.id = String(t.id);
      row.tabIndex = 0;
      row.setAttribute("role", "button");
      row.setAttribute("aria-haspopup", "dialog");

      const direction = document.createElement("span");
      direction.className = "dt-history-direction";
      direction.textContent = directionLabel(t.direction);

      const qty = document.createElement("span");
      qty.className = "dt-history-qty";
      qty.textContent = formatQuantity(t.quantity) + " BTC";

      const prices = document.createElement("span");
      prices.className = "dt-history-price";
      prices.textContent = formatPrice(t.entryPrice) + " → " + formatPrice(t.exitPrice);

      // The final result, computed once by the server in BigDecimal
      // (CONTRACTS.md) -- never recomputed here from the two prices.
      const pnl = document.createElement("span");
      pnl.className = "dt-history-pnl";
      renderSignedUsd(pnl, t.pnl, t.pnlPercent);

      const time = document.createElement("span");
      time.className = "dt-history-time";
      // closedAt is a real zoned instant (CONTRACTS.md) -- do NOT append
      // "Z", same rule as `start`/`priceAt` on getLiveChart.
      time.textContent = formatTradeTime(t.closedAt);

      row.appendChild(direction);
      row.appendChild(qty);
      row.appendChild(prices);
      row.appendChild(pnl);
      row.appendChild(time);
      historyList.appendChild(row);
    });
    show(historyList);
    queueFitHistory();
  }

  // "YYYY-MM-DD" in the viewer's own time zone -- the same form an
  // <input type="date"> gives, so the two compare as plain strings.
  function localDateKey(iso) {
    const d = new Date(iso);
    return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") +
      "-" + String(d.getDate()).padStart(2, "0");
  }

  // Was the trade open at any point on `day`? A trade held overnight
  // belongs to both days, so searching either one finds it.
  function openOnDay(t, day) {
    return localDateKey(t.openedAt) <= day && day <= localDateKey(t.closedAt || new Date());
  }

  historyDirectionInput.addEventListener("change", renderHistoryRows);
  historyDateInput.addEventListener("change", renderHistoryRows);

  historyList.addEventListener("click", function (event) {
    const row = event.target.closest(".dt-history-row");
    if (row) openTradeDialog(Number(row.dataset.id));
  });
  historyList.addEventListener("keydown", function (event) {
    if (event.key !== "Enter" && event.key !== " ") return;
    const row = event.target.closest(".dt-history-row");
    if (!row) return;
    event.preventDefault(); // Space would scroll the list
    openTradeDialog(Number(row.dataset.id));
  });

  // ---- One trade in full (the history's rows open this) -------------------
  // Everything the row has no room for: both moments, how long it was held,
  // the margin it set aside, and the way into the journal for it.

  function openTradeDialog(id) {
    const t = closedTrades.find(function (c) { return c.id === id; });
    if (!t) return;
    dialogTradeId = id;

    tradeDialogTitle.textContent = directionLabel(t.direction) + " " + formatQuantity(t.quantity) + " BTC";
    renderSignedUsd(tradeDialogResult, t.pnl, t.pnlPercent);

    tradeDialogFacts.replaceChildren();
    [
      ["Opened", formatFullTime(t.openedAt)],
      ["Entry price", formatPrice(t.entryPrice)],
      ["Closed", formatFullTime(t.closedAt)],
      ["Exit price", formatPrice(t.exitPrice)],
      ["Held for", formatDuration(Date.parse(t.closedAt) - Date.parse(t.openedAt))],
      ["Margin", formatPrice(t.entryPrice * t.quantity)]
    ].forEach(function (pair) {
      const term = document.createElement("dt");
      term.textContent = pair[0];
      const value = document.createElement("dd");
      value.textContent = pair[1];
      tradeDialogFacts.appendChild(term);
      tradeDialogFacts.appendChild(value);
    });

    tradeDialog.showModal();
  }

  // "29 Sep 2026, 14:03:12" -- seconds included: two trades opened in the
  // same minute are told apart here, and the history row already has the
  // short form.
  function formatFullTime(iso) {
    return new Date(iso).toLocaleString(undefined, {
      day: "numeric", month: "short", year: "numeric",
      hour: "2-digit", minute: "2-digit", second: "2-digit"
    });
  }

  function formatDuration(ms) {
    const minutes = Math.floor(ms / 60000);
    if (minutes < 1) return "under a minute";
    const days = Math.floor(minutes / 1440);
    const hours = Math.floor((minutes % 1440) / 60);
    const mins = minutes % 60;
    if (days > 0) return days + " d " + hours + " h";
    if (hours > 0) return hours + " h " + mins + " min";
    return mins + " min";
  }

  tradeDialogClose.addEventListener("click", function () { tradeDialog.close(); });
  // A click on the backdrop lands on the <dialog> element itself.
  tradeDialog.addEventListener("click", function (event) {
    if (event.target === tradeDialog) tradeDialog.close();
  });
  // Hands the trade to js/journal.js, which opens a new entry
  // already linked to it -- an event, the same seam as tradeschanged.
  tradeDialogJournal.addEventListener("click", function () {
    tradeDialog.close();
    document.dispatchEvent(new CustomEvent("easytrading:journaltrade", {
      detail: { tradeId: dialogTradeId }
    }));
  });

  // Signs follow the ROUNDED value, so nothing ever reads "+$0.00".
  function formatSignedUsd(amount) {
    const shown = Math.round(amount * 100) / 100;
    const sign = shown > 0 ? "+" : shown < 0 ? "\u2212" : "";
    return sign + formatPrice(Math.abs(shown));
  }

  function formatSignedPercent(percent) {
    const shown = Math.round(percent * 100) / 100;
    const sign = shown > 0 ? "+" : shown < 0 ? "\u2212" : "";
    return sign + Math.abs(shown).toFixed(2) + "%";
  }

  // "27 Sep, 18:30" -- date and time, the year only when it isn't this one.
  function formatTradeTime(iso) {
    const d = new Date(iso);
    const opts = { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" };
    if (d.getFullYear() !== new Date().getFullYear()) opts.year = "numeric";
    return d.toLocaleString(undefined, opts);
  }

  function resetTradingUI() {
    lastPrice = null;
    orderDirection = "LONG";
    orderUnit = "USD";
    updateDirectionUI();
    updateUnitUI();
    clearAccount();
    quantityInput.value = "";
    updateOrderPreview(); // back to the 0.00 placeholders, price "—"
    hideTradeMessages();
    if (tradeDialog.open) tradeDialog.close();
    closedTrades = [];
    historyDirectionInput.value = "";
    historyDateInput.value = "";
    historyList.innerHTML = "";
    hide(historyList);
    hide(historyEmpty);
  }

  // ---- Login gate ---------------------------------------------------------
  // user === null means logged out, same convention as auth.js's own render()
  // -- a normal state, not an error.

  document.addEventListener("easytrading:authchange", function (event) {
    const user = event.detail && event.detail.user;

    if (user) {
      hide(gate);
      show(chartSection);
      show(tradingSection);
      show(topbarStats);
      loadTradeHistory();
      if (!started) {
        started = true;
        // The first poll already returns the merged history+live series.
        startPolling();
      }
    } else {
      show(gate);
      hide(chartSection);
      hide(tradingSection);
      hide(topbarStats);
      stopPolling();
      started = false;
      candles = [];
      candleSeconds = 60;
      candleHistory = new Map();
      isLive = true;
      viewEndMs = null;
      isDragging = false;
      hide(jumpLiveButton);
      lastDisplayedPrice = null;
      priceEl.textContent = "—";
      changeIconEl.textContent = "";
      changeIconEl.classList.remove("is-positive", "is-negative", "is-flat");
      hide(staleNotice);
      hide(historyNotice);
      chartContainer.innerHTML = "";
      resetTradingUI();
    }
  });
})();
