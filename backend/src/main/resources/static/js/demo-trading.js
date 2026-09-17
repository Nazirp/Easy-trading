// SCRUM-73 -- the demo trading page's live BTC/USD price chart (UC-03).
//
// Talks to the live-price half of the backend contract (backend/CONTRACTS.md):
//   GET /api/getLiveHistory?symbol=BTC/USD -> 200 {symbol, points:[{datetime, price}]} | 401 | 404
//   GET /api/getLivePrice?symbol=BTC/USD   -> 200 {symbol, price, timestamp, outdated} | 401 | 404 | 503
//
// This is the read-only half of UC-03 (see the "UC-03 -- Demo Trading with
// Live Chart" project doc) -- placing simulated trades against a virtual
// balance is SCRUM-45, a separate ticket this one blocks. This page only
// ever shows BTC/USD: no instrument switcher, no range buttons, nothing
// borrowed from search.js (which this file deliberately does not touch,
// same as search.js and auth.js don't touch each other).
//
// Five things worth knowing before changing anything here:
//
//  1. This page is login-gated, but auth.js is unmodified and knows nothing
//     about that -- it just renders #account-bar and fires
//     "easytrading:authchange" exactly as it does on the search page. All
//     the gating (show the chart vs. a "please log in" prompt, start vs.
//     stop polling) lives here, listening to that one event.
//
//  2. datetime vs. timestamp: getLiveHistory's `datetime` has no zone (same
//     UTC-naive-string issue as getPrice's candles -- append "Z" before
//     parsing). getLivePrice's `timestamp` already carries one and must NOT
//     get a second "Z" appended, or every live point lands an hour or more
//     off from where the history left off.
//
//  3. `outdated: true` on getLivePrice is a normal 200, not an error -- the
//     feed is still alive, just re-serving the last price it has. A poll
//     only ever appends a new chart point when `timestamp` actually moves
//     forward; an outdated response just refreshes the "may be outdated"
//     notice and leaves the chart alone.
//
//  4. Nothing here is ever a hard error. A failed history call opens an
//     empty chart with a short note and still starts polling (there may be
//     live prices even with no history); a failed or 503 poll keeps
//     whatever was last drawn, shows "may be outdated", and tries again on
//     the next 5-second tick.
//
//  5. Polling pauses while the tab is hidden (document.visibilityState) and
//     resumes -- with an immediate poll, not a wait -- when it becomes
//     visible again, so a backgrounded tab doesn't burn Finnhub calls for
//     nothing and a returning user isn't staring at a stale price for up to
//     5 more seconds.

(function () {
  "use strict";

  const SYMBOL = "BTC/USD";
  const POLL_MS = 5000;
  const WINDOW_MS = 30 * 60 * 1000; // rolling 30-minute window
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

  let points = [];            // [{t: epochMs, price: number}], oldest first
  let dividerTime = null;     // epoch ms of the history/live boundary, or null
  let lastTimestampMs = null; // last getLivePrice `timestamp` actually applied
  let lastDisplayedPrice = null; // previous poll's price, for the up/down/flat tick
  let pollHandle = null;
  let active = false;         // the poll loop is running
  let started = false;        // history has been loaded at least once
  let resizeHandle = null;

  function show(el) { el.hidden = false; }
  function hide(el) { el.hidden = true; }

  function parseNoZone(str) {
    return Date.parse(str + "Z");
  }

  function formatPrice(price) {
    return "$" + Number(price).toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  // Compares this poll's price to the previous one -- "since the last 5
  // seconds", literally the last tick, independent of whether this tick
  // also moved the chart (an outdated/duplicate-timestamp response still
  // has a price worth comparing, and correctly shows "flat" when Finnhub
  // re-serves the same number).
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

  async function loadHistory() {
    let response;
    try {
      response = await fetch("/api/getLiveHistory?symbol=" + encodeURIComponent(SYMBOL));
    } catch (networkErr) {
      historyNotice.textContent = "Couldn't load price history — showing live prices only.";
      show(historyNotice);
      return;
    }

    if (!response.ok) {
      // 401 shouldn't happen (the page is gated) and 404 shouldn't happen
      // (BTC/USD always exists) -- either way this degrades the same as a
      // network failure rather than hard-erroring a page that can still
      // work off live polls alone.
      historyNotice.textContent = "Couldn't load price history — showing live prices only.";
      show(historyNotice);
      return;
    }

    let body;
    try {
      body = await response.json();
    } catch (parseErr) {
      historyNotice.textContent = "Couldn't load price history — showing live prices only.";
      show(historyNotice);
      return;
    }

    const raw = (body && body.points) || [];
    if (raw.length === 0) {
      // Empty points is a normal 200 (CONTRACTS.md) -- history unavailable,
      // not an error.
      historyNotice.textContent = "No recent history for BTC/USD yet — showing live prices only.";
      show(historyNotice);
      return;
    }

    points = raw.map(function (p) {
      return { t: parseNoZone(p.datetime), price: p.price };
    });
    dividerTime = points[points.length - 1].t;
    hide(historyNotice);
    drawChart();
  }

  async function pollOnce() {
    let response;
    try {
      response = await fetch("/api/getLivePrice?symbol=" + encodeURIComponent(SYMBOL));
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
      // Covers 503 LIVE_PRICE_UNAVAILABLE and anything else: same visible
      // treatment as a network failure, keep the last drawn price on screen.
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

    if (body.outdated) {
      staleNotice.textContent = STALE_MESSAGE;
      show(staleNotice);
    } else {
      hide(staleNotice);
    }

    const ts = Date.parse(body.timestamp); // already zoned -- no extra "Z"
    if (lastTimestampMs !== null && ts <= lastTimestampMs) {
      // Same price re-served (outdated or not) -- nothing new to plot, but
      // the readout still reflects whatever the server just said.
      renderPrice(body.price);
      return;
    }

    lastTimestampMs = ts;
    points.push({ t: ts, price: body.price });
    pruneOldPoints();
    renderPrice(body.price);
    drawChart();
  }

  function pruneOldPoints() {
    const cutoff = Date.now() - WINDOW_MS;
    while (points.length > 1 && points[0].t < cutoff) {
      points.shift();
    }
    if (dividerTime !== null && dividerTime < cutoff) {
      dividerTime = null;
    }
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

  // ---- Chart: hand-built inline SVG price line (same house style as
  // search.js's candlesticks -- document.createElementNS, redrawn wholesale
  // on data change/resize -- but a plain line here, no candles). ----------

  function drawChart() {
    chartContainer.innerHTML = "";
    if (points.length === 0) return;

    const width = chartContainer.clientWidth || 600;
    const height = chartContainer.clientHeight || 260;
    const padLeft = 58;
    const padRight = 12;
    const padTop = 14;
    const padBottom = 14;

    const cutoff = Date.now() - WINDOW_MS;
    const tStart = Math.min(cutoff, points[0].t);
    const tEnd = Math.max(Date.now(), points[points.length - 1].t);

    let min = points[0].price;
    let max = points[0].price;
    points.forEach(function (p) {
      if (p.price < min) min = p.price;
      if (p.price > max) max = p.price;
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

    function x(t) {
      return padLeft + ((t - tStart) / (tEnd - tStart || 1)) * (width - padLeft - padRight);
    }
    function y(price) {
      return padTop + (1 - (price - min) / (max - min || 1)) * (height - padTop - padBottom);
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

    // Faint divider marking the history -> live boundary, if still in view.
    // The price line itself is not treated specially at this point -- the
    // straight polyline segment on either side of it is the "straight across
    // the gap" the ticket asks for, nothing extra to draw.
    if (dividerTime !== null && dividerTime >= tStart && dividerTime <= tEnd) {
      const dx = x(dividerTime);
      const dline = document.createElementNS(svgNS, "line");
      dline.setAttribute("x1", dx);
      dline.setAttribute("x2", dx);
      dline.setAttribute("y1", padTop);
      dline.setAttribute("y2", height - padBottom);
      dline.setAttribute("style", "stroke: var(--border-strong); stroke-width: 1; stroke-dasharray: 3 3;");
      svg.appendChild(dline);
    }

    const pointsAttr = points.map(function (p) {
      return x(p.t) + "," + y(p.price);
    }).join(" ");
    const polyline = document.createElementNS(svgNS, "polyline");
    polyline.setAttribute("points", pointsAttr);
    polyline.setAttribute("style", "fill: none; stroke: var(--type-crypto); stroke-width: 2; stroke-linejoin: round; stroke-linecap: round;");
    svg.appendChild(polyline);

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
        loadHistory().then(startPolling);
      }
    } else {
      show(gate);
      hide(chartSection);
      stopPolling();
      started = false;
      points = [];
      dividerTime = null;
      lastTimestampMs = null;
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
