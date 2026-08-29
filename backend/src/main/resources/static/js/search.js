// SCRUM-40 — minimal frontend for Search Instrument (UC01), MS3 skeleton.
// Talks to the REAL, finalized backend contract from SCRUM-36 / Confluence:
//   GET /api/search?q={query}
//   GET /api/getPrice?symbol={symbol}&interval={interval}
// (NOT the /api/v1/instruments/... paths in SCRUM-40's own stale ticket text.)
//
// No styling/polish, no other screens (watchlist, demo trading, journal),
// no signal display (that's SCRUM-20) — per SCRUM-40's stated scope.

(function () {
  "use strict";

  const form = document.getElementById("search-form");
  const input = document.getElementById("search-input");
  const searchError = document.getElementById("search-error");
  const emptyState = document.getElementById("empty-state");
  const resultsList = document.getElementById("results-list");

  const detail = document.getElementById("detail");
  const detailTitle = document.getElementById("detail-title");
  const detailError = document.getElementById("detail-error");
  const detailEmpty = document.getElementById("detail-empty");
  const chartContainer = document.getElementById("chart-container");

  // A generic, non-technical fallback for anything that isn't a structured
  // 400/404 from the backend (network down, 500, malformed response, etc.)
  // — mirrors UC01 extension 4a's wording rather than surfacing raw errors.
  const GENERIC_FETCH_FAILURE =
    "We couldn't reach the market data right now — try again in a moment.";

  function hide(el) {
    el.hidden = true;
  }

  function show(el) {
    el.hidden = false;
  }

  function setText(el, text) {
    el.textContent = text;
  }

  function resetSearchMessages() {
    hide(searchError);
    hide(emptyState);
    hide(resultsList);
    resultsList.innerHTML = "";
  }

  function resetDetail() {
    hide(detail);
    hide(detailError);
    hide(detailEmpty);
    chartContainer.innerHTML = "";
  }

  // ---- Search (UC01 steps 2-9) ----------------------------------------

  form.addEventListener("submit", async function (event) {
    event.preventDefault();
    const query = input.value.trim();

    resetSearchMessages();
    resetDetail();

    // UC01 extension 2a: empty query — validated client-side first so we
    // don't even round-trip to the backend for an obviously empty query.
    if (!query) {
      setText(searchError, "Please enter an instrument symbol or name.");
      show(searchError);
      return;
    }

    let response;
    try {
      response = await fetch(
        "/api/search?q=" + encodeURIComponent(query)
      );
    } catch (networkErr) {
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      return;
    }

    let body = null;
    try {
      body = await response.json();
    } catch (parseErr) {
      // Response wasn't JSON at all — treat like any other unreachable case.
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      return;
    }

    if (response.status === 400) {
      // INVALID_QUERY — backend rejected it (shouldn't normally happen since
      // we already checked client-side, but the backend is the source of truth).
      setText(searchError, body.message || "Please enter an instrument symbol or name.");
      show(searchError);
      return;
    }

    if (response.status === 404) {
      // UC01 extension 7a: no matching instruments — friendly empty state,
      // not an error.
      setText(
        emptyState,
        "No results for '" + query + "'. Try a symbol like EUR/USD or BTC."
      );
      show(emptyState);
      return;
    }

    if (!response.ok) {
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      return;
    }

    renderResults(body.results || []);
  });

  function renderResults(results) {
    if (results.length === 0) {
      // Defensive: contract says 200 is always non-empty, but don't assume.
      setText(
        emptyState,
        "No results for '" + input.value.trim() + "'. Try a symbol like EUR/USD or BTC."
      );
      show(emptyState);
      return;
    }

    resultsList.innerHTML = "";
    results.forEach(function (instrument) {
      const li = document.createElement("li");
      li.className = "result-item";

      const button = document.createElement("button");
      button.type = "button";
      // UC01 step 8 / BR3: symbol AND plain-language name, not just the raw ticker.
      button.textContent = instrument.symbol + " — " + instrument.name +
        " (" + instrument.type + ")";
      button.addEventListener("click", function () {
        selectInstrument(instrument);
      });

      li.appendChild(button);
      resultsList.appendChild(li);
    });

    show(resultsList);
  }

  // ---- Selecting a result / chart (UC01 steps 9-11) --------------------

  async function selectInstrument(instrument) {
    resetDetail();
    show(detail);
    setText(detailTitle, instrument.symbol + " — " + instrument.name);

    // The real contract has no "range=30d" param (that was the stale ticket
    // text) — only a fixed interval enum. "1day" is the closest match to a
    // ~30-day daily view and is the contract's own default.
    const interval = "1day";

    let response;
    try {
      response = await fetch(
        "/api/getPrice?symbol=" + encodeURIComponent(instrument.symbol) +
          "&interval=" + encodeURIComponent(interval)
      );
    } catch (networkErr) {
      setText(detailError, GENERIC_FETCH_FAILURE);
      show(detailError);
      return;
    }

    let body = null;
    try {
      body = await response.json();
    } catch (parseErr) {
      setText(detailError, GENERIC_FETCH_FAILURE);
      show(detailError);
      return;
    }

    if (response.status === 404) {
      setText(detailError, "No instrument found for '" + instrument.symbol + "'.");
      show(detailError);
      return;
    }

    if (response.status === 400) {
      setText(detailError, body.message || GENERIC_FETCH_FAILURE);
      show(detailError);
      return;
    }

    if (!response.ok) {
      setText(detailError, GENERIC_FETCH_FAILURE);
      show(detailError);
      return;
    }

    const prices = body.prices || [];
    if (prices.length === 0) {
      // Contract: empty prices array is valid, not an error.
      setText(
        detailEmpty,
        "No price data available for this instrument yet."
      );
      show(detailEmpty);
      return;
    }

    renderChart(prices);
    // Note: body.signal is intentionally not rendered — signal display is
    // SCRUM-20, out of scope here. MS3 always sends verdict "NONE" anyway.
  }

  // ---- Basic chart (placeholder — real charting library is a later
  // polish decision per SCRUM-40's own scope note) ------------------------

  function renderChart(prices) {
    const width = 600;
    const height = 240;
    const padding = 24;

    const closes = prices.map(function (p) { return p.close; });
    const min = Math.min.apply(null, closes);
    const max = Math.max.apply(null, closes);
    const range = max - min || 1; // avoid divide-by-zero on flat data

    const stepX = (width - padding * 2) / Math.max(prices.length - 1, 1);

    const points = closes.map(function (close, i) {
      const x = padding + i * stepX;
      const y = height - padding - ((close - min) / range) * (height - padding * 2);
      return x.toFixed(1) + "," + y.toFixed(1);
    }).join(" ");

    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 " + width + " " + height);
    svg.setAttribute("width", "100%");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Basic price chart placeholder");

    const polyline = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
    polyline.setAttribute("points", points);
    polyline.setAttribute("fill", "none");
    polyline.setAttribute("stroke", "currentColor");
    polyline.setAttribute("stroke-width", "2");

    svg.appendChild(polyline);
    chartContainer.innerHTML = "";
    chartContainer.appendChild(svg);
  }
})();
