// SCRUM-40 / SCRUM-51 — frontend for Search Instrument (UC01).
// Talks to the REAL, finalized backend contract from SCRUM-36 / CONTRACTS.md:
//   GET /api/search?q={query}
//   GET /api/getPrice?symbol={symbol}&interval={interval}
//
// SCRUM-51:
//  - a collapsed "current instrument" pill (defaults to BTC) expands into
//    a floating search card on click — the whole thing behaves like a
//    popover, not a page section.
//  - search fires on every keystroke (debounced ~250ms) instead of on
//    submit, with a loading row shown while a request is in flight.
//  - a request-sequence counter means a slow, stale response can never
//    overwrite the dropdown after a newer keystroke already fired.
//  - results show the instrument type instead of a price — /api/search
//    doesn't return price data, only symbol/name/type.
//  - the price ticker and chart meta line (H/L) are real numbers derived
//    from the /api/getPrice response for whatever is currently selected,
//    not fabricated to match the reference mockup.
//
// No other screens (watchlist, demo trading, journal), no signal display
// (SCRUM-20). The "?" button is decorative — plain-language description
// is SCRUM-43's own feature, not duplicated here.

(function () {
  "use strict";

  const DEBOUNCE_MS = 250;

  // Backend's type enum, lowercased for lookup; anything else falls back
  // to a neutral "other" styling instead of breaking.
  const TYPE_LABELS = { crypto: "Crypto", forex: "Forex", stock: "Stock" };

  // Must match the real DB row (db/seed.sql) exactly, not the friendly
  // "BTC" shorthand — /api/getPrice needs the actual stored symbol or
  // this 404s and no chart loads by default.
  const DEFAULT_INSTRUMENT = { symbol: "BTC/USD", name: "Bitcoin / US Dollar", type: "crypto" };

  const selectorWrap = document.querySelector(".instrument-selector");
  const selectorToggle = document.getElementById("selector-toggle");
  const selectorDot = document.getElementById("selector-dot");
  const selectorSymbol = document.getElementById("selector-symbol");
  const selectorName = document.getElementById("selector-name");

  const dropdownPanel = document.getElementById("dropdown-panel");
  const input = document.getElementById("search-input");
  const searchError = document.getElementById("search-error");
  const emptyState = document.getElementById("empty-state");
  const resultsList = document.getElementById("results-list");

  const tickerPrice = document.getElementById("ticker-price");
  const tickerChange = document.getElementById("ticker-change");

  const detail = document.getElementById("detail");
  const detailTitle = document.getElementById("detail-title");
  const detailMeta = document.getElementById("detail-meta");
  const detailError = document.getElementById("detail-error");
  const detailEmpty = document.getElementById("detail-empty");
  const chartContainer = document.getElementById("chart-container");

  // A generic, non-technical fallback for anything that isn't a structured
  // 400/404 from the backend (network down, 500, malformed response, etc.)
  // — mirrors UC01 extension 4a's wording rather than surfacing raw errors.
  const GENERIC_FETCH_FAILURE =
    "We couldn't reach the market data right now — try again in a moment.";

  let selectedInstrument = null;

  function normalizeType(rawType) {
    const key = (rawType || "").toLowerCase();
    return {
      key: TYPE_LABELS[key] ? key : "other",
      label: TYPE_LABELS[key] || (rawType || "Instrument")
    };
  }

  function hide(el) {
    el.hidden = true;
  }

  function show(el) {
    el.hidden = false;
  }

  function setText(el, text) {
    el.textContent = text;
  }

  function formatNumber(n) {
    // Plain, locale-aware grouping — no currency symbol since instruments
    // span crypto/forex/stocks with different conventions.
    return Number(n).toLocaleString(undefined, { maximumFractionDigits: 4 });
  }

  function resetSearchMessages() {
    hide(searchError);
    hide(emptyState);
  }

  function closeDropdown() {
    hide(resultsList);
    resultsList.innerHTML = "";
    input.setAttribute("aria-expanded", "false");
  }

  function resetDetail() {
    hide(detail);
    hide(detailError);
    hide(detailEmpty);
    setText(detailMeta, "");
    chartContainer.innerHTML = "";
  }

  // ---- Collapsed "current instrument" pill / expandable panel ---------

  function setSelectorDisplay(instrument) {
    const type = normalizeType(instrument.type);
    selectorDot.setAttribute("data-type", type.key);
    setText(selectorSymbol, instrument.symbol);
    setText(selectorName, instrument.name);
    selectorToggle.title = type.label;
  }

  function openPanel() {
    show(dropdownPanel);
    selectorToggle.setAttribute("aria-expanded", "true");
    input.value = "";
    resetSearchMessages();
    closeDropdown();
    input.focus();
  }

  function closePanel() {
    hide(dropdownPanel);
    selectorToggle.setAttribute("aria-expanded", "false");
    if (debounceTimer) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    requestSeq++; // invalidate any in-flight request
    resetSearchMessages();
    closeDropdown();
  }

  selectorToggle.addEventListener("click", function () {
    if (dropdownPanel.hidden) {
      openPanel();
    } else {
      closePanel();
    }
  });

  // Close like any other popover: click elsewhere, or Escape.
  document.addEventListener("click", function (event) {
    if (!dropdownPanel.hidden && !selectorWrap.contains(event.target)) {
      closePanel();
    }
  });

  input.addEventListener("keydown", async function (event) {
    if (event.key === "Escape") {
      closePanel();
      selectorToggle.focus();
      return;
    }

    if (event.key !== "Enter") {
      return;
    }

    // Enter picks the top result — same as clicking it. If a search hasn't
    // fired yet (still debouncing) or is still in flight, wait for it
    // rather than selecting nothing.
    event.preventDefault();
    const query = input.value.trim();
    if (!query) {
      return;
    }

    if (debounceTimer) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
      currentSearchPromise = runSearch(query);
    }

    if (currentSearchPromise) {
      await currentSearchPromise;
    }

    selectFirstResult();
  });

  // ---- Live search (UC01 steps 2-9, SCRUM-51) --------------------------

  let debounceTimer = null;
  // Bumped on every keystroke/clear/close so a slow, stale response can
  // never overwrite the dropdown after a newer request has already started.
  let requestSeq = 0;
  // The in-flight (or most recently started) search, so Enter can await it
  // instead of racing it.
  let currentSearchPromise = null;

  input.addEventListener("input", function () {
    const query = input.value.trim();

    if (debounceTimer) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }

    resetSearchMessages();

    if (!query) {
      // Nothing typed (or just cleared) — nothing to show, no round-trip.
      requestSeq++;
      closeDropdown();
      return;
    }

    debounceTimer = setTimeout(function () {
      currentSearchPromise = runSearch(query);
    }, DEBOUNCE_MS);
  });

  function showLoadingDropdown() {
    resultsList.innerHTML = "";
    const li = document.createElement("li");
    li.className = "result-item result-loading";
    setText(li, "Loading…");
    resultsList.appendChild(li);
    show(resultsList);
    input.setAttribute("aria-expanded", "true");
  }

  async function runSearch(query) {
    const mySeq = ++requestSeq;
    showLoadingDropdown();

    let response;
    try {
      response = await fetch(
        "/api/search?q=" + encodeURIComponent(query)
      );
    } catch (networkErr) {
      if (mySeq !== requestSeq) return; // superseded by a newer keystroke
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      closeDropdown();
      return;
    }

    let body = null;
    try {
      body = await response.json();
    } catch (parseErr) {
      if (mySeq !== requestSeq) return;
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      closeDropdown();
      return;
    }

    if (mySeq !== requestSeq) return; // a later request already took over

    if (response.status === 400) {
      // INVALID_QUERY — backend rejected it (shouldn't normally happen since
      // an empty query never reaches here, but the backend is the source of
      // truth).
      setText(searchError, body.message || "Please enter an instrument symbol or name.");
      show(searchError);
      closeDropdown();
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
      closeDropdown();
      return;
    }

    if (!response.ok) {
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      closeDropdown();
      return;
    }

    renderResults(body.results || [], query);
  }

  function renderResults(results, query) {
    if (results.length === 0) {
      // Defensive: contract says 200 is always non-empty, but don't assume.
      setText(
        emptyState,
        "No results for '" + query + "'. Try a symbol like EUR/USD or BTC."
      );
      show(emptyState);
      closeDropdown();
      return;
    }

    resultsList.innerHTML = "";
    results.forEach(function (instrument) {
      const type = normalizeType(instrument.type);

      const li = document.createElement("li");
      li.className = "result-item";
      li.setAttribute("data-type", type.key);
      if (selectedInstrument && selectedInstrument.symbol === instrument.symbol) {
        li.classList.add("is-current");
      }

      const button = document.createElement("button");
      button.type = "button";

      const dot = document.createElement("span");
      dot.className = "result-dot";
      dot.setAttribute("data-type", type.key);
      dot.setAttribute("aria-hidden", "true");

      const info = document.createElement("span");
      info.className = "result-info";

      const symbolEl = document.createElement("span");
      symbolEl.className = "result-symbol";
      symbolEl.textContent = instrument.symbol;

      const nameEl = document.createElement("span");
      nameEl.className = "result-name";
      nameEl.textContent = instrument.name;

      info.appendChild(symbolEl);
      info.appendChild(nameEl);

      // UC01 step 8 / BR3 asks for symbol + plain-language name; SCRUM-51
      // adds the instrument type here in place of a price.
      const typeBadge = document.createElement("span");
      typeBadge.className = "result-type";
      typeBadge.textContent = type.label;

      button.appendChild(dot);
      button.appendChild(info);
      button.appendChild(typeBadge);

      button.addEventListener("click", function () {
        selectInstrument(instrument);
      });

      li.appendChild(button);
      resultsList.appendChild(li);
    });

    show(resultsList);
    input.setAttribute("aria-expanded", "true");
  }

  // Enter-to-select (see the input keydown handler): picks the same first
  // row a click would, skipping the loading placeholder if one is present.
  function selectFirstResult() {
    const firstButton = resultsList.querySelector(".result-item:not(.result-loading) button");
    if (firstButton) {
      firstButton.click();
    }
  }

  // ---- Selecting a result / chart (UC01 steps 9-11) --------------------

  async function selectInstrument(instrument) {
    selectedInstrument = instrument;
    setSelectorDisplay(instrument);
    closePanel();

    resetDetail();
    show(detail);
    setText(detailTitle, instrument.symbol + " — " + instrument.name);
    setText(tickerPrice, "—");
    hide(tickerChange);

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

    renderTicker(prices, body.interval || interval, type_of(instrument));
    renderChart(prices);
    // Note: body.signal is intentionally not rendered — signal display is
    // SCRUM-20, out of scope here. MS3 always sends verdict "NONE" anyway.
  }

  function type_of(instrument) {
    return normalizeType(instrument.type).label;
  }

  // Real numbers derived from the fetched candle series — last close as
  // the headline price, high/low across the series for the meta line, and
  // (last vs. first close) as a "change over this range" figure. This is
  // NOT a live 24h change (we have no reference price for that); it's
  // labeled by interval so it isn't misread as one.
  function renderTicker(prices, interval, typeLabel) {
    const closes = prices.map(function (p) { return p.close; });
    const highs = prices.map(function (p) { return p.high; });
    const lows = prices.map(function (p) { return p.low; });

    const last = closes[closes.length - 1];
    const first = closes[0];
    const high = Math.max.apply(null, highs);
    const low = Math.min.apply(null, lows);

    setText(tickerPrice, formatNumber(last));

    if (first) {
      const changePct = ((last - first) / first) * 100;
      const sign = changePct > 0 ? "+" : "";
      setText(tickerChange, sign + changePct.toFixed(2) + "% (" + interval + ")");
      tickerChange.classList.toggle("is-positive", changePct > 0);
      tickerChange.classList.toggle("is-negative", changePct < 0);
      show(tickerChange);
    }

    setText(
      detailMeta,
      interval + " · " + typeLabel + " · H " + formatNumber(high) + "  L " + formatNumber(low)
    );
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

  // ---- Default placeholder: BTC selected on load ------------------------
  // "Current chosen instrument" starts as BTC per SCRUM-51; this reuses the
  // exact same selection path (and hits the real /api/getPrice) rather than
  // faking a chart or a price for it.
  selectInstrument(DEFAULT_INSTRUMENT);
})();
