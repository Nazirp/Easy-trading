// SCRUM-71 — the personal watchlist (UC03).
//
// Talks to the watchlist half of the backend contract (backend/CONTRACTS.md):
//   GET    /api/watchlist                  -> 200 {items:[{symbol,name,type}]} | 401
//   POST   /api/watchlist {symbol}         -> 201 {symbol,name,type} | 404 | 409 | 401
//   DELETE /api/watchlist?symbol={symbol}  -> 204 | 401
//
// Three things worth knowing before changing anything here:
//
//  1. The symbol goes in DELETE's query string, never a path segment. Symbols
//     contain slashes ("BTC/USD"), so /api/watchlist/BTC/USD does not route at
//     all. encodeURIComponent still matters, for the "/" and for anything else.
//
//  2. This file never reaches into search.js and search.js knows nothing about
//     watchlists. The two meet at exactly two seams, both offered by search.js:
//     onRenderInstrument() to put the "+" onto a search row or the chart
//     header, and selectInstrument() to load a chart when a saved item is
//     clicked. Auth is the same story -- auth.js announces who is logged in
//     with the "easytrading:authchange" event and nothing more.
//
//  3. The list is never cached across reloads. It is fetched on login and
//     cleared on logout, and `items` below is only a copy of what the server
//     last said -- used to decide whether a "+" should read as "+" or "on your
//     watchlist", never as the source of truth for what is saved.

(function () {
  "use strict";

  const GENERIC_FAILURE = "Couldn't reach your watchlist — try again in a moment.";

  const strip = document.getElementById("watchlist-strip");
  const emptyLabel = document.getElementById("watchlist-empty");
  const list = document.getElementById("watchlist-items");

  /** Symbols currently saved, as the server last reported them. */
  let items = [];
  let signedIn = false;

  // The chart header is re-decorated when the list changes, so remember where
  // it is and what is charted.
  let detailContainer = null;
  let detailInstrument = null;

  function isSaved(symbol) {
    return items.some(function (item) { return item.symbol === symbol; });
  }

  // ---- Hover tooltips ---------------------------------------------------
  //
  // One bubble, appended to <body>, positioned on hover. It started as a CSS
  // ::after on each control, which was simpler but wrong: a pseudo-element is
  // clipped by any scrolling ancestor, and the search results list is
  // `overflow-y: auto`, so the tooltip on the TOP row was cut off by the list
  // it sat in. A fixed-position element in <body> has no clipping ancestor.
  //
  // The handlers are delegated from the document rather than attached per
  // button, so controls created later (every search result, every chip) are
  // covered without anyone remembering to wire them up.

  const tooltip = document.createElement("div");
  tooltip.className = "tooltip-bubble";
  tooltip.setAttribute("role", "presentation");   // aria-label on the control is the accessible name
  document.body.appendChild(tooltip);

  const TOOLTIP_GAP = 6;      // px between the control and the bubble
  const VIEWPORT_MARGIN = 8;  // px the bubble keeps away from the window edges

  function showTooltip(trigger) {
    const text = trigger.getAttribute("data-tooltip");
    if (!text) {
      return;
    }
    tooltip.textContent = text;
    tooltip.classList.add("is-visible");

    // Measure only after the text is in, or the width is last hover's.
    const anchor = trigger.getBoundingClientRect();
    const bubble = tooltip.getBoundingClientRect();

    let left = anchor.left + (anchor.width - bubble.width) / 2;
    // Keep it on screen rather than letting it hang off the side.
    left = Math.max(VIEWPORT_MARGIN,
        Math.min(left, window.innerWidth - bubble.width - VIEWPORT_MARGIN));

    // Above by default; below when there is no room, which is what happens to
    // a control near the top of the window.
    let top = anchor.top - bubble.height - TOOLTIP_GAP;
    if (top < VIEWPORT_MARGIN) {
      top = anchor.bottom + TOOLTIP_GAP;
    }

    tooltip.style.left = Math.round(left) + "px";
    tooltip.style.top = Math.round(top) + "px";
  }

  function hideTooltip() {
    tooltip.classList.remove("is-visible");
  }

  document.addEventListener("mouseover", function (event) {
    const trigger = event.target.closest("[data-tooltip]");
    if (trigger) {
      showTooltip(trigger);
    }
  });

  document.addEventListener("mouseout", function (event) {
    if (event.target.closest("[data-tooltip]")) {
      hideTooltip();
    }
  });

  // Keyboard users get the same explanation.
  document.addEventListener("focusin", function (event) {
    const trigger = event.target.closest("[data-tooltip]");
    if (trigger) {
      showTooltip(trigger);
    }
  });
  document.addEventListener("focusout", hideTooltip);

  // A click usually replaces the control that is being described (the "+"
  // becomes a tick), and a bubble pointing at an element that no longer exists
  // is worse than no bubble. Scrolling moves the control out from under it for
  // the same reason.
  document.addEventListener("click", hideTooltip);
  window.addEventListener("scroll", hideTooltip, true);

  // ---- The "+" control --------------------------------------------------

  // Called by search.js for every search result row and for the chart header.
  // Removes its own previous button first: the same container is re-rendered
  // whenever the list changes, and without this each render would leave another
  // "+" behind.
  function decorate(container, instrument) {
    // The chart header is the one container that is not a search row. Remember
    // it, so the button on it can be refreshed after an add or a remove without
    // waiting for the chart to be redrawn.
    if (container.classList.contains("detail-header")) {
      detailContainer = container;
      detailInstrument = instrument;
    }

    const existing = container.querySelector(".watchlist-add");
    if (existing) {
      existing.remove();
    }
    container.appendChild(buildAddButton(instrument));
  }

  function buildAddButton(instrument) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "watchlist-add";
    button.setAttribute("data-symbol", instrument.symbol);

    const saved = isSaved(instrument.symbol);
    // Never icon-only for a screen reader: the glyph is decorative and the
    // real label lives in aria-label, which is also what the CSS tooltip shows.
    const label = saved ? "On your watchlist" : "Add to watchlist";
    button.textContent = saved ? "✓" : "+";
    button.setAttribute("aria-label", label);
    button.setAttribute("data-tooltip", label);
    button.classList.toggle("is-saved", saved);
    button.disabled = saved;

    button.addEventListener("click", function (event) {
      // The button sits inside the result row's own button, which selects the
      // instrument -- without this, adding to the watchlist would also load
      // the chart.
      event.stopPropagation();
      event.preventDefault();
      add(instrument);
    });
    return button;
  }

  function refreshButtons() {
    // Search rows: re-render in place from the symbol each button carries.
    document.querySelectorAll(".result-item .watchlist-add").forEach(function (button) {
      const symbol = button.getAttribute("data-symbol");
      const saved = isSaved(symbol);
      const label = saved ? "On your watchlist" : "Add to watchlist";
      button.textContent = saved ? "✓" : "+";
      button.setAttribute("aria-label", label);
      button.setAttribute("data-tooltip", label);
      button.classList.toggle("is-saved", saved);
      button.disabled = saved;
    });
    // The chart header, if something is charted.
    if (detailContainer && detailInstrument) {
      decorate(detailContainer, detailInstrument);
    }
  }

  // ---- Rendering the strip ----------------------------------------------

  function render() {
    list.innerHTML = "";

    if (items.length === 0) {
      emptyLabel.textContent = "Nothing saved yet.";
      emptyLabel.hidden = false;
      list.hidden = true;
      refreshButtons();
      return;
    }

    emptyLabel.hidden = true;
    items.forEach(function (item) {
      const li = document.createElement("li");
      li.className = "watchlist-item";
      li.setAttribute("data-type", (item.type || "").toLowerCase());

      // Clicking the instrument loads its chart -- the whole point of saving
      // it is not having to search for it again.
      const open = document.createElement("button");
      open.type = "button";
      open.className = "watchlist-open";
      open.textContent = item.symbol;
      open.setAttribute("aria-label", "Show the chart for " + item.symbol);
      open.setAttribute("data-tooltip", item.name);
      open.addEventListener("click", function () {
        // Straight through search.js's own selectInstrument, so the current
        // range and everything else behave exactly as they do for a search
        // result.
        window.EasyTrading.selectInstrument(item);
      });

      const removeButton = document.createElement("button");
      removeButton.type = "button";
      removeButton.className = "watchlist-remove";
      removeButton.textContent = "×";
      removeButton.setAttribute("aria-label", "Remove " + item.symbol + " from your watchlist");
      removeButton.setAttribute("data-tooltip", "Remove from watchlist");
      removeButton.addEventListener("click", function () {
        remove(item.symbol);
      });

      li.appendChild(open);
      li.appendChild(removeButton);
      list.appendChild(li);
    });
    list.hidden = false;
    refreshButtons();
  }

  function say(message, isError) {
    // The strip has one line for feedback; reuse the empty-state slot rather
    // than growing the layout, and put it back afterwards.
    emptyLabel.textContent = message;
    emptyLabel.hidden = false;
    emptyLabel.classList.toggle("is-error", Boolean(isError));
    window.setTimeout(function () {
      emptyLabel.classList.remove("is-error");
      render();
    }, 2500);
  }

  // ---- Talking to the backend --------------------------------------------

  async function load() {
    let response;
    try {
      response = await fetch("/api/watchlist");
    } catch (networkErr) {
      items = [];
      render();
      return;
    }

    if (response.status === 401) {
      // The session went away between the page loading and this request.
      items = [];
      signedIn = false;
      render();
      return;
    }

    if (!response.ok) {
      items = [];
      render();
      return;
    }

    try {
      const body = await response.json();
      items = body.items || [];
    } catch (parseErr) {
      items = [];
    }
    render();
  }

  async function add(instrument) {
    // UC03 extension 2a: logged out is not an error, it is a prompt.
    if (!signedIn) {
      promptLogin();
      return;
    }

    let response;
    try {
      response = await fetch("/api/watchlist", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ symbol: instrument.symbol })
      });
    } catch (networkErr) {
      say(GENERIC_FAILURE, true);
      return;
    }

    if (response.status === 401) {
      signedIn = false;
      promptLogin();
      return;
    }

    if (response.status === 409) {
      // Already saved. Information, not a failure -- what the user wanted is
      // already true, so the list is refreshed and the message is neutral.
      say("Already on your watchlist.", false);
      await load();
      return;
    }

    if (!response.ok) {
      let body = null;
      try {
        body = await response.json();
      } catch (parseErr) {
        body = null;
      }
      say((body && body.message) || GENERIC_FAILURE, true);
      return;
    }

    try {
      // 201 returns the saved instrument, so the new row can be rendered from
      // the response instead of re-fetching the whole list.
      items = items.concat([await response.json()]);
    } catch (parseErr) {
      await load();
      return;
    }
    render();
  }

  async function remove(symbol) {
    if (!signedIn) {
      promptLogin();
      return;
    }

    let response;
    try {
      // encodeURIComponent, because "BTC/USD" has a slash in it.
      response = await fetch("/api/watchlist?symbol=" + encodeURIComponent(symbol), { method: "DELETE" });
    } catch (networkErr) {
      say(GENERIC_FAILURE, true);
      return;
    }

    if (response.status === 401) {
      signedIn = false;
      promptLogin();
      return;
    }

    if (!response.ok) {
      say(GENERIC_FAILURE, true);
      return;
    }

    items = items.filter(function (item) { return item.symbol !== symbol; });
    render();
  }

  function promptLogin() {
    // auth.js owns the dialog; this is the same control the prompt line uses.
    const trigger = document.querySelector("[data-auth-open='login']");
    if (trigger) {
      trigger.click();
    }
  }

  // ---- Wiring -------------------------------------------------------------

  // auth.js fires this once on page load and again on every login and logout,
  // so one listener covers the initial render as well as every change.
  document.addEventListener("easytrading:authchange", function (event) {
    signedIn = Boolean(event.detail && event.detail.user);
    if (signedIn) {
      load();
    } else {
      // Nothing of the previous user's stays on screen.
      items = [];
      render();
    }
  });

  // search.js calls this for every search result row and for the chart header.
  if (window.EasyTrading && window.EasyTrading.onRenderInstrument) {
    window.EasyTrading.onRenderInstrument(decorate);
  }
})();
