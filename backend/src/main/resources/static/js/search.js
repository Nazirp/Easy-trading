// SCRUM-40 / SCRUM-51 / SCRUM-63 / SCRUM-65 — frontend for Search
// Instrument (UC01) and the historical price chart with its signal
// (UC02 BR1).
// Talks to the REAL, finalized backend contract from SCRUM-36 / CONTRACTS.md:
//   GET /api/search?q={query}
//   GET /api/getPrice?symbol={symbol}&interval={interval}
//
// SCRUM-51 (search):
//  - a collapsed "current instrument" pill (defaults to BTC) expands into
//    a floating search card on click.
//  - search fires on every keystroke (debounced ~250ms), guarded so a slow
//    stale response can't overwrite a newer one; Enter selects the top
//    result exactly like clicking it.
//  - results show the instrument type instead of a price — /api/search
//    doesn't return price data, only symbol/name/type.
//
// SCRUM-63 (chart range switcher):
//  - four range buttons (1W/1M/6M/1YR) map to the backend's interval enum
//    (2h/4h/1day/1week per CONTRACTS.md); the mapping is the whole contract,
//    nothing else about the request changes.
//  - the range survives switching instruments; switching either one re-fetches
//    the chart through the same loadChart() path.
//  - reuses the "only the newest request may render" guard pattern from the
//    search box (factored into createRequestGuard()) instead of
//    reimplementing it for the chart.
//  - a loading state covers the chart while a fetch is in flight — getPrice
//    can hit Twelve Data on a cache miss and take a second or more.
//  - users only ever see 1W/1M/6M/1YR; interval codes never reach the UI,
//    including the ticker's "% change" label (this previously leaked the
//    raw interval — fixed here).
//  - real candlesticks, hand-built as inline SVG -- no charting library,
//    no vendor folder, no <script> dependency beyond this file itself.
//  - daily/weekly candles use Lightweight Charts' business-day time format
//    (no time-of-day component at all) instead of a UNIX timestamp, so a
//    6M/1YR chart can't show a misleading "00:00" on every bar the way a
//    literal timestamp with a hidden clock would.
//
// SCRUM-65 (signal):
//  - renders alongside the chart, from the same response as the prices —
//    no separate request, no separate cache. Always present whenever a
//    chart renders (a chart without its signal is not a valid state per
//    UC02 BR1), including the neutral NONE verdict, which is what every
//    instrument returns today until SCRUM-64 (the real crossover
//    calculation) lands. NONE is a normal 200, not error styling.
//  - never colour-only: BUY/SELL/HOLD/NONE each pair a colour with an
//    icon, so a red-green colourblind viewer can still read the verdict.
//  - label/explanation text is the backend's own plain language, rendered
//    as-is — no wording invented here.
//  - a short "not financial advice" line sits under the badge.
//
// No other screens (watchlist, demo trading, journal). The "?" button is
// decorative — plain-language description is SCRUM-43's own feature, not
// duplicated here.

(function () {
  "use strict";

  const DEBOUNCE_MS = 250;

  // Backend's type enum, lowercased for lookup; anything else falls back
  // to a neutral "other" styling instead of breaking.
  const TYPE_LABELS = { crypto: "Crypto", forex: "Forex", stock: "Stock" };

  // Icon always accompanies colour so the verdict reads without relying on
  // red/green perception (SCRUM-65).
  const SIGNAL_ICONS = { BUY: "▲", SELL: "▼", HOLD: "●", NONE: "–" };

  const DEFAULT_INSTRUMENT = { symbol: "BTC/USD", name: "Bitcoin / US Dollar", type: "crypto" };

  // SCRUM-63's whole contract: which interval each user-facing range sends.
  const RANGE_TO_INTERVAL = { "1w": "2h", "1m": "4h", "6m": "1day", "1yr": "1week" };
  const RANGE_LABELS = { "1w": "1W", "1m": "1M", "6m": "6M", "1yr": "1YR" };
  // Not specified by the ticket (CONTRACTS.md flags the backend's own
  // default as "not the frontend's default view, open decision") — 1M
  // picked as a reasonable middle-ground default.
  const DEFAULT_RANGE = "1m";

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
  const chartLoading = document.getElementById("chart-loading");
  const chartContainer = document.getElementById("chart-container");

  const chartTooltip = document.getElementById("chart-tooltip");

  const signalPanel = document.getElementById("signal-panel");
  const signalBadge = document.getElementById("signal-badge");
  const signalIcon = document.getElementById("signal-icon");
  const signalLabel = document.getElementById("signal-label");
  const signalExplanation = document.getElementById("signal-explanation");
  const rangeButtons = document.querySelectorAll(".range-button");

  // A generic, non-technical fallback for anything that isn't a structured
  // 400/404 from the backend (network down, 500, malformed response, etc.)
  // — mirrors UC01 extension 4a's wording rather than surfacing raw errors.
  const GENERIC_FETCH_FAILURE =
    "We couldn't reach the market data right now — try again in a moment.";

  let selectedInstrument = null;
  let currentRange = DEFAULT_RANGE;

  function normalizeType(rawType) {
    const key = (rawType || "").toLowerCase();
    return {
      key: TYPE_LABELS[key] ? key : "other",
      label: TYPE_LABELS[key] || (rawType || "Instrument")
    };
  }

  function typeLabelFor(instrument) {
    return normalizeType(instrument.type).label;
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

  // "Only the newest request may render" — written once for the search box
  // (SCRUM-51), reused here for the chart (SCRUM-63) instead of duplicating
  // the same counter/comparison logic twice.
  function createRequestGuard() {
    let seq = 0;
    return {
      start: function () { return ++seq; },
      isCurrent: function (id) { return id === seq; },
      invalidate: function () { seq++; }
    };
  }

  const searchGuard = createRequestGuard();
  const chartGuard = createRequestGuard();

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
    hide(chartLoading);
    hide(signalPanel);
    setText(detailMeta, "");
    clearChart();
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
    searchGuard.invalidate(); // invalidate any in-flight request
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
      searchGuard.invalidate();
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
    const mySeq = searchGuard.start();
    showLoadingDropdown();

    let response;
    try {
      response = await fetch(
        "/api/search?q=" + encodeURIComponent(query)
      );
    } catch (networkErr) {
      if (!searchGuard.isCurrent(mySeq)) return; // superseded by a newer keystroke
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      closeDropdown();
      return;
    }

    let body = null;
    try {
      body = await response.json();
    } catch (parseErr) {
      if (!searchGuard.isCurrent(mySeq)) return;
      setText(searchError, GENERIC_FETCH_FAILURE);
      show(searchError);
      closeDropdown();
      return;
    }

    if (!searchGuard.isCurrent(mySeq)) return; // a later request already took over

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

  // ---- Range switcher (SCRUM-63) ----------------------------------------

  function setActiveRangeButton(range) {
    currentRange = range;
    rangeButtons.forEach(function (btn) {
      btn.classList.toggle("is-active", btn.getAttribute("data-range") === range);
    });
  }

  rangeButtons.forEach(function (button) {
    button.addEventListener("click", function () {
      const range = button.getAttribute("data-range");
      if (range === currentRange || !selectedInstrument) {
        return;
      }
      setActiveRangeButton(range);
      // Range survives switching instruments and vice versa — both paths
      // go through the same loadChart(), just with whichever changed.
      loadChart(selectedInstrument, range);
    });
  });

  // ---- Selecting a result (UC01 steps 9-11) -----------------------------

  async function selectInstrument(instrument) {
    selectedInstrument = instrument;
    setSelectorDisplay(instrument);
    closePanel();

    resetDetail();
    show(detail);
    setText(detailTitle, instrument.symbol + " — " + instrument.name);
    setText(tickerPrice, "—");
    hide(tickerChange);

    await loadChart(instrument, currentRange);
  }

  // ---- Chart data (SCRUM-63) ---------------------------------------------

  async function loadChart(instrument, range) {
    const interval = RANGE_TO_INTERVAL[range];
    const rangeLabel = RANGE_LABELS[range];
    const myId = chartGuard.start();

    hide(detailError);
    hide(detailEmpty);
    hide(signalPanel);
    show(chartLoading);

    let response;
    try {
      response = await fetch(
        "/api/getPrice?symbol=" + encodeURIComponent(instrument.symbol) +
          "&interval=" + encodeURIComponent(interval)
      );
    } catch (networkErr) {
      if (!chartGuard.isCurrent(myId)) return; // a newer range/instrument request took over
      hide(chartLoading);
      setText(detailError, GENERIC_FETCH_FAILURE);
      show(detailError);
      return;
    }

    let body = null;
    try {
      body = await response.json();
    } catch (parseErr) {
      if (!chartGuard.isCurrent(myId)) return;
      hide(chartLoading);
      setText(detailError, GENERIC_FETCH_FAILURE);
      show(detailError);
      return;
    }

    // Clicking 1w then 6m quickly could otherwise let the slower 1w
    // response land last and paint the wrong chart under the 6m button.
    if (!chartGuard.isCurrent(myId)) return;

    hide(chartLoading);

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
      // Contract: empty prices array is a neutral empty state, not an
      // error — some instruments are thin at some ranges (see SCRUM-63:
      // ~18 points for AAPL at 1w/2h vs ~84 for BTC, which trades 24/7).
      setText(
        detailEmpty,
        "No price data available for this instrument yet."
      );
      show(detailEmpty);
      clearChart();
      return;
    }

    renderTicker(prices, rangeLabel, typeLabelFor(instrument));
    renderChart(prices, interval);
    renderSignal(body.signal);
  }

  // A chart without its signal is not a valid state (UC02 BR1) -- this
  // always runs right after a successful renderChart, from the exact same
  // /api/getPrice response, never a second request or a separate cache.
  function renderSignal(signal) {
    const verdict = (signal && signal.verdict) || "NONE";
    const label = (signal && signal.label) || "No signal available.";
    const explanation = signal && signal.explanation;

    signalBadge.setAttribute("data-verdict", verdict);
    setText(signalIcon, SIGNAL_ICONS[verdict] || SIGNAL_ICONS.NONE);
    setText(signalLabel, label);

    if (explanation) {
      setText(signalExplanation, explanation);
      show(signalExplanation);
    } else {
      hide(signalExplanation);
    }

    show(signalPanel);
  }

  // Real numbers derived from the fetched candle series — last close as
  // the headline price, high/low across the series for the meta line, and
  // (last vs. first close) as a "change over this range" figure. This is
  // NOT a live 24h change (we have no reference price for that); it's
  // labeled by the user-facing range (never the raw interval — that leaked
  // here before SCRUM-63 and is fixed now).
  function renderTicker(prices, rangeLabel, typeLabel) {
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
      setText(tickerChange, sign + changePct.toFixed(2) + "% (" + rangeLabel + ")");
      tickerChange.classList.toggle("is-positive", changePct > 0);
      tickerChange.classList.toggle("is-negative", changePct < 0);
      show(tickerChange);
    }

    setText(
      detailMeta,
      rangeLabel + " · " + typeLabel + " · H " + formatNumber(high) + "  L " + formatNumber(low)
    );
  }

  // ---- Candlestick chart (SCRUM-63) --------------------------------------
  // No third-party library, by design: candles are built as a plain inline
  // SVG, redrawn from scratch on every range/instrument change and on
  // window resize. A full rebuild is cheap here -- CONTRACTS.md puts the
  // largest range (1w@2h) at ~84 candles for BTC.

  let lastPrices = null;
  let lastInterval = null;
  let lastLayout = null; // geometry from the most recent drawChart(), for hover hit-testing
  let crosshairLine = null; // the SVG line element that follows the cursor

  function clearChart() {
    chartContainer.innerHTML = "";
    lastPrices = null;
    lastInterval = null;
    lastLayout = null;
    crosshairLine = null;
    hide(chartTooltip);
  }

  function renderChart(prices, interval) {
    lastPrices = prices;
    lastInterval = interval;
    drawChart(prices, interval);
  }

  function drawChart(prices, interval) {
    const svgNS = "http://www.w3.org/2000/svg";
    const width = chartContainer.clientWidth || 600;
    const height = chartContainer.clientHeight || 320;

    const padLeft = 8, padRight = 44, padTop = 12, padBottom = 22;
    const plotW = Math.max(1, width - padLeft - padRight);
    const plotH = Math.max(1, height - padTop - padBottom);

    const highs = prices.map(function (p) { return p.high; });
    const lows = prices.map(function (p) { return p.low; });
    const max = Math.max.apply(null, highs);
    const min = Math.min.apply(null, lows);
    const range = (max - min) || 1;

    function yFor(value) {
      return padTop + (1 - (value - min) / range) * plotH;
    }

    const n = prices.length;
    const slot = plotW / n;
    const bodyWidth = Math.max(1, Math.min(10, slot * 0.6));

    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("viewBox", "0 0 " + width + " " + height);
    svg.setAttribute("width", "100%");
    svg.setAttribute("height", "100%");
    svg.setAttribute("preserveAspectRatio", "none");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Candlestick price chart");

    // Geometry the hover handler needs to map a mouse X back to a candle --
    // recomputed here (not stored per-candle) since it's cheap and this
    // already runs on every redraw.
    lastLayout = { prices: prices, padLeft: padLeft, slot: slot, n: n, height: height, width: width };

    // Gridlines + price labels at the low, mid and high of the visible range.
    [0, 0.5, 1].forEach(function (frac) {
      const value = min + frac * range;
      const y = yFor(value);

      const line = document.createElementNS(svgNS, "line");
      line.setAttribute("x1", padLeft);
      line.setAttribute("x2", width - padRight);
      line.setAttribute("y1", y);
      line.setAttribute("y2", y);
      line.setAttribute("style", "stroke: var(--border); stroke-width: 1;");
      svg.appendChild(line);

      const label = document.createElementNS(svgNS, "text");
      label.setAttribute("x", width - padRight + 4);
      label.setAttribute("y", y + 3);
      label.setAttribute("style", "fill: var(--text-tertiary); font-size: 10px;");
      label.textContent = formatNumber(value);
      svg.appendChild(label);
    });

    // Each candle: a wick (high-low) plus a body (open-close), colored the
    // same up/down green/red as the rest of the app.
    prices.forEach(function (p, i) {
      const cx = padLeft + slot * i + slot / 2;
      const isUp = p.close >= p.open;
      const colorVar = isUp ? "var(--positive)" : "var(--negative)";

      const wick = document.createElementNS(svgNS, "line");
      wick.setAttribute("x1", cx);
      wick.setAttribute("x2", cx);
      wick.setAttribute("y1", yFor(p.high));
      wick.setAttribute("y2", yFor(p.low));
      wick.setAttribute("style", "stroke: " + colorVar + "; stroke-width: 1;");
      svg.appendChild(wick);

      const openY = yFor(p.open);
      const closeY = yFor(p.close);
      const body = document.createElementNS(svgNS, "rect");
      body.setAttribute("x", cx - bodyWidth / 2);
      body.setAttribute("y", Math.min(openY, closeY));
      body.setAttribute("width", bodyWidth);
      body.setAttribute("height", Math.max(1, Math.abs(closeY - openY)));
      body.setAttribute("style", "fill: " + colorVar + ";");
      svg.appendChild(body);
    });

    // A handful of date labels (first/middle/last), not one per candle --
    // most ranges hold far more candles than there is room to label.
    const intraday = interval === "2h" || interval === "4h";
    const tickIndexes = n === 1 ? [0] : [0, Math.floor((n - 1) / 2), n - 1];
    const labeled = {};
    tickIndexes.forEach(function (i) {
      if (labeled[i]) return;
      labeled[i] = true;
      const cx = padLeft + slot * i + slot / 2;
      const anchor = i === 0 ? "start" : i === n - 1 ? "end" : "middle";
      const label = document.createElementNS(svgNS, "text");
      label.setAttribute("x", cx);
      label.setAttribute("y", height - 6);
      label.setAttribute("text-anchor", anchor);
      label.setAttribute("style", "fill: var(--text-tertiary); font-size: 10px;");
      label.textContent = formatAxisDate(prices[i].datetime, intraday);
      svg.appendChild(label);
    });

    // Crosshair: a single vertical line, hidden until the first mousemove,
    // then just repositioned in place -- never rebuilt except by a full
    // redraw (range/instrument change or resize).
    crosshairLine = document.createElementNS(svgNS, "line");
    crosshairLine.setAttribute("y1", padTop);
    crosshairLine.setAttribute("y2", height - padBottom);
    crosshairLine.setAttribute("style", "stroke: var(--text-tertiary); stroke-width: 1; stroke-dasharray: 3,3;");
    crosshairLine.setAttribute("visibility", "hidden");
    svg.appendChild(crosshairLine);

    chartContainer.innerHTML = "";
    chartContainer.appendChild(svg);
  }

  // ---- Hover crosshair + OHLC tooltip -------------------------------------
  // Attached once to the container (not rebuilt on redraw): chartContainer's
  // innerHTML is replaced wholesale on every drawChart(), but a listener on
  // the container element itself survives that, same as the resize handler.

  chartContainer.addEventListener("mousemove", function (event) {
    if (!lastLayout || !crosshairLine) return;

    const rect = chartContainer.getBoundingClientRect();
    const mouseX = event.clientX - rect.left;

    const raw = Math.round((mouseX - lastLayout.padLeft - lastLayout.slot / 2) / lastLayout.slot);
    const i = Math.max(0, Math.min(lastLayout.n - 1, raw));
    const p = lastLayout.prices[i];
    const cx = lastLayout.padLeft + lastLayout.slot * i + lastLayout.slot / 2;

    crosshairLine.setAttribute("x1", cx);
    crosshairLine.setAttribute("x2", cx);
    crosshairLine.setAttribute("visibility", "visible");

    chartTooltip.innerHTML =
      "<strong>" + formatTooltipDate(p.datetime) + "</strong>" +
      tooltipRow("Open", p.open) +
      tooltipRow("High", p.high) +
      tooltipRow("Low", p.low) +
      tooltipRow("Close", p.close);

    // Flip to the left of the cursor past the halfway point so the tooltip
    // never runs off the right edge of the chart.
    const tooltipWidth = 150;
    const left = cx > lastLayout.width / 2 ? cx - tooltipWidth - 12 : cx + 12;
    chartTooltip.style.left = Math.max(4, left) + "px";
    show(chartTooltip);
  });

  chartContainer.addEventListener("mouseleave", function () {
    if (crosshairLine) {
      crosshairLine.setAttribute("visibility", "hidden");
    }
    hide(chartTooltip);
  });

  function tooltipRow(label, value) {
    return (
      "<div class=\"tooltip-row\"><span>" + label + "</span><span>" +
      formatNumber(value) + "</span></div>"
    );
  }

  // Always shows date + time (in UTC, matching the backend's stored
  // datetimes) regardless of interval -- unlike the axis labels, the
  // tooltip has room, and hiding the time on daily/weekly candles would
  // hide real information (they still land at UTC midnight).
  function formatTooltipDate(datetimeStr) {
    const d = new Date(Date.parse(datetimeStr + "Z"));
    const day = d.getUTCDate();
    const month = MONTH_NAMES[d.getUTCMonth()];
    const year = d.getUTCFullYear();
    const hh = String(d.getUTCHours()).padStart(2, "0");
    const mm = String(d.getUTCMinutes()).padStart(2, "0");
    return day + " " + month + " " + year + ", " + hh + ":" + mm + " UTC";
  }

  // Redraws the last-rendered candles on resize -- there is no library
  // autosize to lean on anymore, and the SVG's viewBox is fixed at draw
  // time to the container's size at that moment.
  let resizeTimer = null;
  window.addEventListener("resize", function () {
    if (!lastPrices) return;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      drawChart(lastPrices, lastInterval);
    }, 100);
  });

  const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

  // Same UTC-safe parsing the chart used before this rewrite: backend datetimes are
  // naive LocalDateTime, stored (and meant) as UTC -- see db/schema.sql.
  // Without an explicit "Z", Date parses a date-time string as LOCAL time,
  // silently shifting intraday bars by the viewer's UTC offset; appending
  // "Z" and reading UTC calendar fields avoids that (and avoids a viewer
  // behind UTC losing a calendar day on daily/weekly labels too).
  function formatAxisDate(datetimeStr, intraday) {
    const d = new Date(Date.parse(datetimeStr + "Z"));
    const day = d.getUTCDate();
    const month = MONTH_NAMES[d.getUTCMonth()];
    if (!intraday) {
      return day + " " + month;
    }
    const hh = String(d.getUTCHours()).padStart(2, "0");
    const mm = String(d.getUTCMinutes()).padStart(2, "0");
    return day + " " + month + " " + hh + ":" + mm;
  }

  // ---- Default placeholder: BTC selected on load ------------------------
  // "Current chosen instrument" starts as BTC/USD per SCRUM-51 (the real DB
  // symbol — see db/seed.sql; the bare "BTC" 404s), at the default range.
  setActiveRangeButton(DEFAULT_RANGE);
  selectInstrument(DEFAULT_INSTRUMENT);
})();
