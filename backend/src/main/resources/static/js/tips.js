// Tips & tricks — the "what am I actually looking at?" layer for someone
// opening a trading app for the first time.
//
// Three ways into the same content, all fed from the single TIPS array
// below so a piece of wording only ever exists once:
//
//  1. the "?" button in the header row, which opens the full list;
//  2. the small "?" dots sitting next to individual controls (chart type,
//     range, watchlist, the signal badge) — each opens a popover for just
//     that topic, right where the question occurs;
//  3. the tip strip under the account bar, which shows one tip at a time.
//
// Markup contract: any element with data-help="<id>" becomes a trigger for
// that topic, wherever it is on the page and whenever it is added, so a
// later story can drop a dot next to a new control without touching this
// file (clicks are handled by one delegated listener on document).
//
// data-help, not data-tooltip: watchlist.js already owns data-tooltip for
// its hover bubble (one line, appears on hover, no interaction). These are
// a different thing — click to open, several paragraphs, a way through to
// the full list — and sharing the attribute would mean two listeners
// fighting over the same elements.
//
// auth.js has a matching [data-help] guard: the watchlist strip is marked
// data-locked-until-auth as a whole, and without it the "?" in that strip
// would open the sign-in dialog rather than the tip.
//
// Deliberately standalone: it reads no app state, calls no endpoint, and
// nothing else depends on it. Loading order relative to search.js /
// watchlist.js / auth.js does not matter.
//
// Tone rule for anything added here: explain the mechanism and the common
// beginner mistake, never tell anyone what to buy. The app's own line —
// "not financial advice, for learning purposes only" — applies to this
// content too, and the last tip says so out loud.

(function () {
  "use strict";

  // Categories exist for the filter row in the dialog. Keep the key short:
  // it is written into a data attribute and compared as-is.
  const CATEGORIES = [
    { key: "all", label: "All" },
    { key: "risk", label: "Risk" },
    { key: "charts", label: "Charts" },
    { key: "signals", label: "Signals" },
    { key: "mindset", label: "Mindset" },
    { key: "platform", label: "This app" }
  ];

  // id       — what data-help points at; also the anchor in the dialog.
  // headline — one line, shown in the strip. Must stand alone.
  // title    — the heading in the popover and the dialog.
  // body     — paragraphs. Plain sentences, no jargon left unexplained.
  const TIPS = [
    {
      id: "risk-per-trade",
      category: "risk",
      title: "Risk a small, fixed slice per trade",
      headline: "Decide what a trade may cost you before you open it — not while it is losing.",
      body: [
        "Before you open a position, decide the most you are willing to lose on it. A common beginner's rule is 1–2% of the account on any single trade.",
        "The maths is the whole point: ten losing trades in a row at 1% each still leaves about 90% of the account, and you can keep learning. Ten in a row at 20% leaves nothing, and no later good decision can undo that."
      ]
    },
    {
      id: "position-sizing",
      category: "risk",
      title: "Position size comes from your stop, not your confidence",
      headline: "Position size is a calculation, not a feeling: risk ÷ distance to your stop.",
      body: [
        "Work it backwards. You know how much you will risk (say $100) and the price at which you would admit you were wrong (say 5% below where you are buying). The size that fits is $100 ÷ 5% = $2,000 of the instrument.",
        "Feeling especially sure about a trade is not a reason to size it larger. That feeling is at its strongest right before crowded, obvious trades — exactly the ones that tend to disappoint."
      ]
    },
    {
      id: "stop-loss",
      category: "risk",
      title: "Know your exit before your entry",
      headline: "Write down both exits — wrong and right — before you enter.",
      body: [
        "Two prices, written down before you enter: the one that proves the idea wrong, and the one that means it worked. If you cannot name the first one, you do not have a trade, you have a hope.",
        "Deciding either price while the position is open is the moment fear and hope start trading for you. That is where beginners turn a small planned loss into a large unplanned one."
      ]
    },
    {
      id: "costs",
      category: "risk",
      title: "Spread, fees and slippage come out of every trade",
      headline: "The price you see is not the price you get — costs land on every single trade.",
      body: [
        "Buying and selling happen at slightly different prices (the spread), a broker takes a fee, and in a fast market your order can fill a little away from where you clicked (slippage).",
        "A strategy that wins by a hair before costs loses after them. The more often you trade, the more of your result is decided by costs rather than by your ideas."
      ]
    },
    {
      id: "candles",
      category: "charts",
      title: "How to read a candlestick",
      headline: "A candle's body is open-to-close; the thin wicks are the high and the low.",
      body: [
        "Each candle covers one slice of time. The thick body spans the opening and closing price of that slice; the thin wicks above and below reach the highest and lowest price traded inside it. Green means it closed above where it opened, red means below.",
        "A long wick is a rejection: price went there and did not stay. A body with almost no wicks means one side was in control the whole slice.",
        "Hover any candle on the chart to read its exact open, high, low and close. Switch to Line if you only care about closing prices — the line is the same data with the highs, lows and opens thrown away."
      ]
    },
    {
      id: "timeframes",
      category: "charts",
      title: "Pick a timeframe and stay on it",
      headline: "1W is mostly noise, 1YR is mostly trend — the same move looks different on each.",
      body: [
        "A shorter range shows more noise and more detail; a longer one shows the trend but hides the bumps. A move that looks dramatic on 1W is often invisible on 1YR, and a slow slide on 1YR can look like calm on 1W.",
        "Changing range until you find the one that agrees with you is a well-known way to talk yourself into a trade. Choose the range that matches how long you intend to hold, and judge the trade on that one.",
        "The signal badge is recalculated for whichever range you are viewing, so it can legitimately disagree with itself between ranges."
      ]
    },
    {
      id: "trend",
      category: "charts",
      title: "Trend, support and resistance",
      headline: "Higher highs and higher lows is an uptrend — until it isn't.",
      body: [
        "An uptrend is a series of higher highs and higher lows; a downtrend is the opposite. Price often stalls around levels it has turned at before — those get called support below and resistance above.",
        "These are tendencies with no mechanism forcing them to hold. Levels break, and a level everyone can see is a level everyone is trading against. Treat them as places to pay attention, not as guarantees."
      ]
    },
    {
      id: "volatility",
      category: "charts",
      title: "Not every instrument moves the same",
      headline: "The same % risk means a very different position size in crypto, forex and stocks.",
      body: [
        "Crypto can move several percent in a day and trades around the clock. Major forex pairs usually move a fraction of that. Individual stocks sit in between, only trade during market hours, and can gap overnight — opening far from where they closed, straight past any stop.",
        "This is why a position size copied from one market is wrong in another. Size against how much that instrument actually moves, not against a habit."
      ]
    },
    {
      id: "signals",
      category: "signals",
      title: "What the signal badge actually is",
      headline: "The badge is one moving-average crossover — an observation, not an instruction.",
      body: [
        "It compares two moving averages of the closing price: a fast 10-candle average against a slower 20-candle one. If the fast one has recently crossed above the slow one it reads BUY, recently crossed below reads SELL, otherwise HOLD. NONE means there was not enough history on that range to calculate it.",
        "Both averages are built from prices that have already happened, so a crossover always confirms a move after it has started. In a sideways market they cross back and forth repeatedly and are wrong most of the time.",
        "One indicator is one opinion. It knows nothing about why the price moved, what is scheduled for tomorrow, or what you can afford to lose."
      ]
    },
    {
      id: "past-performance",
      category: "signals",
      title: "Every pattern is obvious in hindsight",
      headline: "Patterns are obvious in the middle of a chart and invisible at its right edge.",
      body: [
        "Scroll to the middle of any chart and the right entry is plain to see. The right-hand edge — where you actually have to decide — never looks like that, because the bars that made the pattern obvious have not happened yet.",
        "That gap is why a setup which looks flawless on history can feel impossible live, and why past performance is not a promise about the next trade."
      ]
    },
    {
      id: "fomo",
      category: "mindset",
      title: "FOMO and revenge trading",
      headline: "Chasing a move you missed and doubling up after a loss are the two most expensive habits.",
      body: [
        "The two most expensive beginner habits are chasing a move that already happened, and immediately sizing up to win back a loss. Both replace a plan with a mood, and both tend to arrive right after a big candle.",
        "A usable check: if you are placing a trade to feel better rather than because your plan called for it, close the tab. The market will still be here tomorrow."
      ]
    },
    {
      id: "journal",
      category: "mindset",
      title: "Write down why you took the trade",
      headline: "One line before every trade: what you expect, why, and what would prove you wrong.",
      body: [
        "Before entering, write one line: what you expect to happen, why, and what would prove you wrong. It takes seconds and it is the only record of what you were actually thinking, as opposed to what you will remember thinking.",
        "Reading a month of those lines back is the fastest way to find out whether you have an edge or just a habit — and it catches the trades you take out of boredom."
      ]
    },
    {
      id: "demo-first",
      category: "platform",
      title: "Practise here before risking real money",
      headline: "Demo Trading gives you $10,000 in virtual funds — trade it like it's real or it teaches you nothing.",
      body: [
        "Signing up gives you $10,000 in virtual funds on the Demo Trading page, with live BTC/USD prices.",
        "Use position sizes you would genuinely use with your own money. Practice at ten times the size you could really afford teaches the wrong instincts, and those are the instincts you will bring with you."
      ]
    },
    {
      id: "watchlist",
      category: "platform",
      title: "Follow a few instruments, not fifty",
      headline: "Learn how a handful of instruments behave instead of skimming dozens.",
      body: [
        "Save the few instruments you actually want to understand to your watchlist and watch how they behave — how far they usually move in a day, how they react to news, when they are quiet.",
        "Breadth is not an edge for a beginner. Knowing one market properly beats having an opinion about twenty."
      ]
    },
    {
      id: "not-advice",
      category: "platform",
      title: "This app does not tell you what to do",
      headline: "Everything here is educational — no part of it is financial advice.",
      body: [
        "Easy Trading shows prices, draws charts and calculates one textbook indicator. It does not know your finances, your timeline or your tolerance for losing money, so nothing it displays can be a recommendation.",
        "Everything here — including the signal badge and these tips — is for learning. It is not financial advice."
      ]
    }
  ];

  const BY_ID = {};
  TIPS.forEach(function (tip) {
    BY_ID[tip.id] = tip;
  });

  // Collapsed-state memory only. Nothing here is worth a server round trip,
  // and a browser with storage blocked must still get a working strip --
  // hence the try/catch on both sides rather than a feature test.
  const STORE_KEY = "easytrading.tips.collapsed";

  function readCollapsed() {
    try {
      return window.localStorage.getItem(STORE_KEY) === "1";
    } catch (e) {
      return false;
    }
  }

  function writeCollapsed(collapsed) {
    try {
      window.localStorage.setItem(STORE_KEY, collapsed ? "1" : "0");
    } catch (e) {
      /* private mode / storage disabled -- the strip still works, it just
         forgets between visits. */
    }
  }

  // ---- Shared bits ------------------------------------------------------

  function paragraphsInto(parent, tip) {
    tip.body.forEach(function (text) {
      const p = document.createElement("p");
      p.className = "tip-text";
      p.textContent = text;
      parent.appendChild(p);
    });
  }

  function categoryLabel(key) {
    for (let i = 0; i < CATEGORIES.length; i++) {
      if (CATEGORIES[i].key === key) return CATEGORIES[i].label;
    }
    return key;
  }

  // ---- The popover (one element, reused by every "?" dot) ---------------

  let popover = null;
  let popoverTitle = null;
  let popoverBody = null;
  let popoverMore = null;
  let openAnchor = null;

  function buildPopover() {
    popover = document.createElement("div");
    popover.className = "tip-popover";
    popover.id = "tip-popover";
    popover.setAttribute("role", "dialog");
    popover.setAttribute("aria-label", "Explanation");
    popover.tabIndex = -1;
    popover.hidden = true;

    popoverTitle = document.createElement("h3");
    popoverTitle.className = "tip-popover-title";

    popoverBody = document.createElement("div");
    popoverBody.className = "tip-popover-body";

    popoverMore = document.createElement("button");
    popoverMore.type = "button";
    popoverMore.className = "tip-popover-more";
    popoverMore.textContent = "All tips →";
    popoverMore.addEventListener("click", function () {
      const id = popover.dataset.tipId;
      closePopover(false);
      openDialog(id);
    });

    popover.appendChild(popoverTitle);
    popover.appendChild(popoverBody);
    popover.appendChild(popoverMore);
    document.body.appendChild(popover);
  }

  // Anchored under its "?" (or above it, if that would run off the bottom)
  // and clamped to the viewport, so a dot near the right edge of the header
  // row still gets a fully visible card. position: fixed, so this is all in
  // viewport coordinates and needs no scroll offsets.
  function positionPopover(anchor) {
    const gap = 8;
    const margin = 8;
    const a = anchor.getBoundingClientRect();
    const p = popover.getBoundingClientRect();

    let left = a.left + a.width / 2 - p.width / 2;
    left = Math.max(margin, Math.min(left, window.innerWidth - p.width - margin));

    let top = a.bottom + gap;
    if (top + p.height > window.innerHeight - margin) {
      const above = a.top - p.height - gap;
      top = above >= margin ? above : Math.max(margin, window.innerHeight - p.height - margin);
    }

    popover.style.left = Math.round(left) + "px";
    popover.style.top = Math.round(top) + "px";
  }

  function openPopover(anchor, id) {
    const tip = BY_ID[id];
    if (!tip) return;
    if (!popover) buildPopover();

    if (openAnchor && openAnchor !== anchor) closePopover(false);

    popover.dataset.tipId = tip.id;
    popoverTitle.textContent = tip.title;
    popoverBody.innerHTML = "";
    paragraphsInto(popoverBody, tip);

    // Measured while hidden-but-laid-out: the element needs a box before
    // positionPopover can read its height, but must not flash at (0,0).
    popover.style.visibility = "hidden";
    popover.hidden = false;
    positionPopover(anchor);
    popover.style.visibility = "";

    anchor.setAttribute("aria-expanded", "true");
    openAnchor = anchor;
    popover.focus();
  }

  // restoreFocus is false when something else is about to take focus (the
  // dialog), true when the popover simply closed and the "?" should get it
  // back -- otherwise a keyboard user lands back at the top of the page.
  function closePopover(restoreFocus) {
    if (!popover || popover.hidden) return;
    popover.hidden = true;
    if (openAnchor) {
      openAnchor.setAttribute("aria-expanded", "false");
      if (restoreFocus) openAnchor.focus();
      openAnchor = null;
    }
  }

  // ---- The full dialog --------------------------------------------------

  const dialog = document.getElementById("tips-dialog");
  const dialogList = document.getElementById("tips-list");
  const dialogFilter = document.getElementById("tips-filter");
  const dialogClose = document.getElementById("tips-close");

  let activeFilter = "all";

  function buildFilter() {
    if (!dialogFilter) return;
    CATEGORIES.forEach(function (cat) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "tips-filter-button" + (cat.key === "all" ? " is-active" : "");
      button.dataset.category = cat.key;
      button.textContent = cat.label;
      button.setAttribute("aria-pressed", cat.key === "all" ? "true" : "false");
      button.addEventListener("click", function () {
        setFilter(cat.key);
      });
      dialogFilter.appendChild(button);
    });
  }

  function setFilter(key) {
    activeFilter = key;
    if (dialogFilter) {
      Array.prototype.forEach.call(dialogFilter.children, function (button) {
        const on = button.dataset.category === key;
        button.classList.toggle("is-active", on);
        button.setAttribute("aria-pressed", on ? "true" : "false");
      });
    }
    Array.prototype.forEach.call(dialogList.children, function (card) {
      card.hidden = key !== "all" && card.dataset.category !== key;
    });
  }

  // <details> rather than a hand-rolled accordion: open/close, keyboard
  // support and "find on page" expanding the right card all come for free.
  function buildList() {
    if (!dialogList) return;
    TIPS.forEach(function (tip) {
      const card = document.createElement("details");
      card.className = "tip-card";
      card.dataset.category = tip.category;
      card.dataset.tipId = tip.id;

      const summary = document.createElement("summary");
      summary.className = "tip-card-summary";

      const tag = document.createElement("span");
      tag.className = "tip-card-tag";
      tag.dataset.category = tip.category;
      tag.textContent = categoryLabel(tip.category);

      const title = document.createElement("span");
      title.className = "tip-card-title";
      title.textContent = tip.title;

      summary.appendChild(tag);
      summary.appendChild(title);
      card.appendChild(summary);
      paragraphsInto(card, tip);
      dialogList.appendChild(card);
    });
  }

  // focusId, when given, is the tip the user asked about -- it opens
  // expanded and scrolled into view, so arriving from a "?" dot or from the
  // strip does not drop you at the top of a list of fifteen headings.
  function openDialog(focusId) {
    if (!dialog) return;
    closePopover(false);

    if (focusId && BY_ID[focusId]) {
      setFilter("all");
      Array.prototype.forEach.call(dialogList.children, function (card) {
        card.open = card.dataset.tipId === focusId;
      });
    }

    if (!dialog.open) dialog.showModal();

    if (focusId && BY_ID[focusId]) {
      const target = dialogList.querySelector('[data-tip-id="' + focusId + '"]');
      // After showModal, so the dialog has a scrollable box to scroll in.
      if (target) target.scrollIntoView({ block: "start" });
    } else {
      dialogList.scrollTop = 0;
    }
  }

  if (dialog) {
    buildFilter();
    buildList();

    if (dialogClose) {
      dialogClose.addEventListener("click", function () {
        dialog.close();
      });
    }

    // Click outside the card closes it. A <dialog>'s own box is the whole
    // backdrop area, so "was the click inside the content rectangle?" is
    // the check -- clicking the backdrop reports the dialog as the target.
    dialog.addEventListener("click", function (event) {
      if (event.target !== dialog) return;
      const r = dialog.getBoundingClientRect();
      const inside =
        event.clientX >= r.left && event.clientX <= r.right &&
        event.clientY >= r.top && event.clientY <= r.bottom;
      if (!inside) dialog.close();
    });
  }

  // ---- The strip --------------------------------------------------------

  const strip = document.getElementById("tips-strip");
  const stripText = document.getElementById("tips-current");
  const stripPrev = document.getElementById("tips-prev");
  const stripNext = document.getElementById("tips-next");
  const stripAll = document.getElementById("tips-open-all");
  const stripToggle = document.getElementById("tips-toggle");

  // Starts on a different tip each day instead of always tip #1 (which
  // nobody reads twice), and deliberately does not auto-advance: text that
  // changes under you while you are reading it is worse than no text.
  function tipOfTheDay() {
    const days = Math.floor(Date.now() / 86400000);
    return days % TIPS.length;
  }

  let stripIndex = tipOfTheDay();

  function renderStrip() {
    if (!stripText) return;
    const tip = TIPS[stripIndex];
    stripText.textContent = tip.headline;
    stripText.dataset.tipId = tip.id;
    stripText.title = tip.title + " — click to read more";
  }

  function step(delta) {
    stripIndex = (stripIndex + delta + TIPS.length) % TIPS.length;
    renderStrip();
  }

  function setCollapsed(collapsed, persist) {
    if (!strip) return;
    strip.classList.toggle("is-collapsed", collapsed);
    if (stripToggle) {
      stripToggle.setAttribute("aria-expanded", collapsed ? "false" : "true");
      stripToggle.setAttribute("aria-label", collapsed ? "Show tips" : "Hide tips");
    }
    if (persist) writeCollapsed(collapsed);
  }

  if (strip) {
    renderStrip();
    setCollapsed(readCollapsed(), false);

    if (stripPrev) stripPrev.addEventListener("click", function () { step(-1); });
    if (stripNext) stripNext.addEventListener("click", function () { step(1); });
    if (stripAll) stripAll.addEventListener("click", function () { openDialog(null); });
    if (stripText) {
      stripText.addEventListener("click", function () {
        openDialog(stripText.dataset.tipId);
      });
    }
    if (stripToggle) {
      stripToggle.addEventListener("click", function () {
        setCollapsed(!strip.classList.contains("is-collapsed"), true);
      });
    }
  }

  // ---- Triggers ---------------------------------------------------------
  //
  // One delegated listener rather than a listener per dot: markup added
  // later (a new control, a re-rendered panel) is wired up automatically.

  document.addEventListener("click", function (event) {
    // A click can land on something without closest() (the document itself,
    // an SVG in older engines) -- that is a dismiss, not a trigger.
    const target = event.target;
    const trigger = target && target.closest ? target.closest("[data-help]") : null;
    if (trigger) {
      event.preventDefault();
      const id = trigger.dataset.help;
      // data-help="all" is the header "?" -- the whole list, not one topic.
      if (id === "all") {
        openDialog(null);
      } else if (trigger === openAnchor && popover && !popover.hidden) {
        closePopover(true);
      } else {
        openPopover(trigger, id);
      }
      return;
    }
    // Any click that isn't on a trigger and isn't inside the open popover
    // dismisses it.
    if (popover && !popover.hidden && !popover.contains(target)) {
      closePopover(false);
    }
  });

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") closePopover(true);
  });

  // The popover is positioned once, in viewport coordinates, so anything
  // that moves its anchor invalidates it. Closing is the honest response --
  // re-positioning mid-scroll makes a card that chases the cursor.
  window.addEventListener("scroll", function () { closePopover(false); }, true);
  window.addEventListener("resize", function () { closePopover(false); });
})();
