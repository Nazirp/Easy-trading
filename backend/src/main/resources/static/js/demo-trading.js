// SCRUM-77 -- the demo trading page's live BTC/USD candlestick chart (UC-03).
// Supersedes the SCRUM-73 line-chart version: that one called two endpoints
// (getLiveHistory once, getLivePrice every 5s) and kept its own running
// series in a JS array. This version calls ONE endpoint every second and
// redraws candlesticks wholesale from whatever it returns -- there is no
// client-side series to accumulate or prune any more, because the merged
// history+live candle series now lives on the server (backend/CONTRACTS.md
// SS1, SCRUM-76). A page reload restores the same chart instead of
// restarting from a backfill, because nothing here was ever the source of
// truth for it.
//
// Talks to the live-chart half of the backend contract:
//   GET /api/getLiveChart?symbol=BTC/USD
//     -> 200 {symbol, candleSeconds, candles:[{start, open, high, low,
//              close, live, forming}], price, priceAt, outdated} | 401 | 404
//
// This is the read-only half of UC-03 (see the "UC-03 -- Demo Trading with
// Live Chart" project doc) -- placing simulated trades against a virtual
// balance is SCRUM-45, a separate ticket this one blocks. This page only
// ever shows BTC/USD: no instrument switcher, no range buttons, nothing
// borrowed from search.js (which this file deliberately does not touch,
// same as search.js and auth.js don't touch each other).
//
// Six things worth knowing before changing anything here:
//
//  1. This page is login-gated, but auth.js is unmodified and knows nothing
//     about that -- it just renders #account-bar and fires
//     "easytrading:authchange" exactly as it does on the search page. All
//     the gating (show the chart vs. a "please log in" prompt, start vs.
//     stop polling) lives here, listening to that one event.
//
//  2. The "Z" trap: `start` and `priceAt` on THIS endpoint are already
//     zoned instants (they end in "Z"), so `new Date(c.start)` /
//     `Date.parse(c.start)` is correct as-is -- do NOT append a "Z" the way
//     the old getLiveHistory's `datetime` needed (that was the 2026-09-08
//     bug, and getPrice's candle `datetime` still needs it today). The
//     field is named `start`, not `datetime`, specifically so the two
//     conventions are told apart by name rather than by memory.
//
//  3. `outdated: true` no longer decides whether the chart redraws (that
//     was the old getLivePrice rule, and it is gone along with that
//     endpoint). Every poll redraws the chart from whatever `candles` it
//     receives, full stop. `outdated` now means only "no trade has arrived
//     recently, the feed may have stalled" -- a normal 200, shown as a
//     small notice, never a reason to blank the chart.
//
//  4. Only the last candle (`forming: true`, at most one, always last)
//     still has room to change -- everything before it is sealed. Because
//     the whole array is redrawn every poll rather than diffed, this falls
//     out for free: the forming candle's body/wick just end up in a
//     slightly different place next redraw, and nothing else moves.
//
//  5. A gap is real and stays a gap. If a minute saw no trades, the server
//     sends no candle for it at all -- `start` values can jump by more than
//     `candleSeconds`. Candles are positioned on the X axis by their real
//     `start` time (not by array index), so a missing minute shows up as
//     genuine blank space rather than being hidden. This is deliberately
//     NOT index-based spacing (which was tried in between): a future
//     hover/crosshair feature needs to map an arbitrary pixel position back
//     to a real timestamp, and that only works if pixel position actually
//     corresponds to elapsed time. The cost is that a long Finnhub outage
//     visibly compresses the real candles into a narrower stretch of the
//     chart while it's happening -- each candle keeps a floor width so it
//     never fully disappears, but the chart is allowed to look temporarily
//     denser during a bad outage rather than lie about when things happened.
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

  const gate = document.getElementById("dt-gate");
  const chartSection = document.getElementById("dt-chart-section");
  const priceEl = document.getElementById("dt-price");
  const changeIconEl = document.getElementById("dt-change-icon");
  const staleNotice = document.getElementById("dt-stale-notice");
  const historyNotice = document.getElementById("dt-history-notice");
  const chartContainer = document.getElementById("dt-chart-container");

  // Same icon+colour pairing convention as the signal badge (SCRUM-65) --
  // never colour-only, so a red-green colourblind viewer can still tell an
  // uptick from a downtick.
  const CHANGE_ICONS = { up: "▲", down: "▼", flat: "●" };

  // The candle series is a plain cache of the last server response, kept
  // only so a window resize can redraw without waiting for the next poll --
  // it is never accumulated, pruned or merged client-side (see point 1
  // above). candleSeconds defaults to 60 (today's only value) so a redraw
  // triggered before the first poll answers doesn't divide by zero.
  let candles = [];            // [{start, open, high, low, close, live, forming}]
  let candleSeconds = 60;
  let lastDisplayedPrice = null; // previous poll's price, for the up/down/flat tick
  let pollHandle = null;
  let active = false;         // the poll loop is running
  let started = false;        // polling has been started at least once
  let resizeHandle = null;

  function show(el) { el.hidden = false; }
  function hide(el) { el.hidden = true; }

  function formatPrice(price) {
    return "$" + Number(price).toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
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

    if (candles.length === 0) {
      // Empty candles is a normal 200 (CONTRACTS.md) -- history unavailable
      // right now, not an error. price/priceAt are null in this case, so
      // the price readout below is simply left as whatever it last showed.
      historyNotice.textContent = "No recent history for BTC/USD yet — showing live prices only.";
      show(historyNotice);
    } else {
      hide(historyNotice);
    }

    // `outdated` no longer gates the chart redraw (see point 3 in the file
    // header) -- it only toggles this notice. The chart redraws below
    // either way, from whatever candles this poll returned.
    if (body.outdated) {
      staleNotice.textContent = STALE_MESSAGE;
      show(staleNotice);
    } else {
      hide(staleNotice);
    }

    if (typeof body.price === "number") {
      renderPrice(body.price);
    }

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
  // up/down). Candles are placed on the X axis by their real `start` time
  // rather than by array index, specifically so a missing minute (point 5
  // in the file header) shows up as real blank space instead of two
  // candles sitting shoulder to shoulder. ----------------------------------

  function drawChart() {
    chartContainer.innerHTML = "";
    if (candles.length === 0) return;

    const width = chartContainer.clientWidth || 600;
    const height = chartContainer.clientHeight || 260;
    const padLeft = 58;
    const padRight = 12;
    const padTop = 14;
    const padBottom = 14;
    const plotWidth = width - padLeft - padRight;
    const plotHeight = height - padTop - padBottom;

    // Positioned by real elapsed time, not array index -- see point 5 in
    // the file header: this is what lets a future hover/crosshair feature
    // map a pixel position back to an honest timestamp.
    const bucketMs = candleSeconds * 1000;
    const tStart = Date.parse(candles[0].start);
    const tEnd = Date.parse(candles[candles.length - 1].start) + bucketMs;
    const spanMs = (tEnd - tStart) || bucketMs;

    let min = candles[0].low;
    let max = candles[0].high;
    candles.forEach(function (c) {
      if (c.low < min) min = c.low;
      if (c.high > max) max = c.high;
    });
    if (min === max) { min -= 1; max += 1; }
    const pricePad = (max - min) * 0.08;
    min -= pricePad;
    max += pricePad;

    const svgNS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("viewBox", "0 0 " + width + " " + height);
    svg.setAttribute("width", "100%");
    svg.setAttribute("height", "100%");
    svg.setAttribute("aria-label", "Live BTC/USD candlestick chart");

    function x(t) {
      return padLeft + ((t - tStart) / spanMs) * plotWidth;
    }
    function y(price) {
      return padTop + (1 - (price - min) / (max - min || 1)) * plotHeight;
    }

    // Gridlines + price labels.
    const rows = 3;
    for (let i = 0; i <= rows; i++) {
      const price = min + ((max - min) * i) / rows;
      const gy = y(price);

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

    // Faint divider marking the history -> live boundary: the first candle
    // whose `live` flips from false to true. Naturally absent once every
    // candle in the window is live (SCRUM-77 §4.5) -- there is simply no
    // index where that flip happens any more.
    const dividerIndex = candles.findIndex(function (c, i) {
      return i > 0 && c.live && !candles[i - 1].live;
    });
    if (dividerIndex > 0) {
      const dx = x(Date.parse(candles[dividerIndex].start));
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
    // `start` time. A gap between two candles is simply blank space here --
    // no separate marker needed, the honest axis already shows it. Body
    // width is proportional to the real 60-second bucket but floored so a
    // candle never shrinks to nothing during a bad outage. The still-
    // forming candle (at most one, always last) is drawn slightly
    // translucent so it visibly reads as "still moving" rather than sealed.
    const bodyWidth = Math.max(3, (bucketMs / spanMs) * plotWidth * 0.6);
    candles.forEach(function (c) {
      const cx = x(Date.parse(c.start) + bucketMs / 2);
      const isUp = c.close >= c.open;
      const colorVar = isUp ? "var(--positive)" : "var(--negative)";
      const opacity = c.forming ? " opacity: 0.75;" : "";

      const wick = document.createElementNS(svgNS, "line");
      wick.setAttribute("x1", cx);
      wick.setAttribute("x2", cx);
      wick.setAttribute("y1", y(c.high));
      wick.setAttribute("y2", y(c.low));
      wick.setAttribute("style", "stroke: " + colorVar + "; stroke-width: 1.5;" + opacity);
      svg.appendChild(wick);

      const openY = y(c.open);
      const closeY = y(c.close);
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
  }

  window.addEventListener("resize", function () {
    window.clearTimeout(resizeHandle);
    resizeHandle = window.setTimeout(drawChart, 100);
  });

  // ---- Login gate ---------------------------------------------------------
  // user === null means logged out, same convention as auth.js's own render()
  // -- a normal state, not an error.

  document.addEventListener("easytrading:authchange", function (event) {
    const user = event.detail && event.detail.user;

    if (user) {
      hide(gate);
      show(chartSection);
      if (!started) {
        started = true;
        // No separate history bootstrap any more (point 1, file header) --
        // the first poll already returns the merged history+live series.
        startPolling();
      }
    } else {
      show(gate);
      hide(chartSection);
      stopPolling();
      started = false;
      candles = [];
      candleSeconds = 60;
      lastDisplayedPrice = null;
      priceEl.textContent = "—";
      changeIconEl.textContent = "";
      changeIconEl.classList.remove("is-positive", "is-negative", "is-flat");
      hide(staleNotice);
      hide(historyNotice);
      chartContainer.innerHTML = "";
    }
  });
})();
