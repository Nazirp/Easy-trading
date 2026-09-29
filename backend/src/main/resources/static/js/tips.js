// Tips & tricks — the "what am I actually looking at?" layer for someone
// opening a trading app for the first time.
//
// Presented as a SIDE PANEL, not a modal. That is the whole design decision
// and everything else follows from it: an explanation of the chart is worth
// nothing if reading it means covering up the chart. The panel slides in on
// the right, the page behind it stays live and clickable, and it holds its
// place while you switch instrument or range and watch what changes.
//
// Three ways in, all fed from the single TIPS array below so a piece of
// wording only ever exists once:
//
//  1. the "?" in the header row — opens the panel on the browsable list;
//  2. the small "?" dots next to individual controls — open the panel
//     straight onto that one topic;
//  3. the tip strip under the account bar — one headline at a time.
//
// Markup contract: any element with data-help="<id>" becomes a trigger for
// that topic, wherever it is on the page and whenever it is added, so a
// later story can drop a dot next to a new control without touching this
// file (clicks are handled by one delegated listener on document).
//
// data-help, not data-tooltip: watchlist.js already owns data-tooltip for
// its hover bubble (one line, appears on hover, no interaction). These are
// a different thing, and sharing the attribute would mean two listeners
// fighting over the same elements.
//
// auth.js has a matching [data-help] guard: the watchlist strip is marked
// data-locked-until-auth as a whole, and without it the "?" in that strip
// would open the sign-in dialog rather than the tip.
//
// Tone rule for anything added here: explain the mechanism and the common
// beginner mistake, never tell anyone what to buy. The app's own line —
// "not financial advice, for learning purposes only" — applies to this
// content too, and the last tip says so out loud.

(function () {
  "use strict";

  // ---- Diagrams ---------------------------------------------------------
  //
  // Inline SVG, hand-written, no library — same call as the price chart
  // itself (see search.js). Each one is a schematic, not a plot of real
  // data: the job is to make one idea visible, so the numbers are chosen
  // for legibility.
  //
  // Colour never comes from an attribute here. SVG presentation attributes
  // cannot read a CSS custom property (fill="var(--positive)" silently does
  // nothing), so every shape carries a class and tips.css supplies the
  // paint from the same :root tokens the rest of the app uses. That also
  // means a palette change in style.css reaches these for free.

  const DIAGRAMS = {

    // The anatomy of one candle: body = open→close, wicks = high and low.
    candleAnatomy: function () {
      return svg(320, 196,
        // leader lines from the candle out to each label
        dash(80, 20, 150, 20) + dash(99, 60, 150, 60) +
        dash(99, 134, 150, 134) + dash(80, 174, 150, 174) +
        // the candle: one wick line behind, body on top
        '<line class="dg-wick" x1="80" y1="20" x2="80" y2="174"/>' +
        '<rect class="dg-up" x="61" y="60" width="38" height="74" rx="2"/>' +
        txt(158, 24, "High", "dg-key") + txt(158, 64, "Close", "dg-key") +
        txt(158, 138, "Open", "dg-key") + txt(158, 178, "Low", "dg-key") +
        txt(200, 24, "highest traded", "dg-note") +
        txt(208, 64, "where it ended", "dg-note") +
        txt(204, 138, "where it began", "dg-note") +
        txt(196, 178, "lowest traded", "dg-note"),
        "A single candlestick with its high, close, open and low labelled."
      );
    },

    // The same instrument at two ranges. Left is noisy, right is the trend;
    // both end in the same place, which is the entire point.
    timeframes: function () {
      const jagged = "14,96 28,74 42,101 56,66 70,92 84,58 98,86 112,49 126,77 140,42";
      const smooth = "176,104 200,95 224,88 248,72 272,60 296,42";
      return svg(320, 158,
        frame(8, 26, 140, 96) + frame(170, 26, 140, 96) +
        '<polyline class="dg-series" points="' + jagged + '"/>' +
        '<polyline class="dg-series" points="' + smooth + '"/>' +
        txt(8, 18, "1W", "dg-key") + txt(170, 18, "1YR", "dg-key") +
        txt(8, 144, "noise and detail", "dg-note") +
        txt(170, 144, "direction only", "dg-note"),
        "The same rising price shown as a jagged one-week chart and a smooth one-year chart."
      );
    },

    // Two moving averages crossing, and the gap before anyone can act on it.
    crossover: function () {
      return svg(320, 168,
        frame(8, 18, 302, 108) +
        // the shaded lag band, drawn first so the lines sit on top
        '<rect class="dg-band" x="196" y="18" width="70" height="108"/>' +
        '<polyline class="dg-slow" points="16,96 60,92 104,86 148,78 192,68 236,58 280,46 302,40"/>' +
        '<polyline class="dg-fast" points="16,112 60,104 104,92 148,80 192,64 236,44 280,30 302,24"/>' +
        '<circle class="dg-mark" cx="196" cy="66" r="5"/>' +
        dashV(196, 18, 126) +
        txt(16, 12, "fast (10)", "dg-key dg-key-accent") +
        txt(92, 12, "slow (20)", "dg-key") +
        txt(140, 148, "they cross", "dg-note") +
        txt(232, 148, "you act", "dg-note") +
        arrow(200, 140, 262, 140),
        "A fast moving average crossing above a slow one, with a shaded gap before the crossing can be acted on."
      );
    },

    // Ten losses in a row at 1% versus at 20%. Nothing persuades like the
    // second line hitting the floor.
    riskDecay: function () {
      const safe = "30,24 57,25 84,26 111,27 138,28 165,29 192,30 219,31 246,32 273,33 300,34";
      const wipe = "30,24 57,46 84,64 111,78 138,89 165,98 192,105 219,111 246,116 273,119 300,122";
      return svg(320, 168,
        frame(24, 18, 286, 112) +
        '<polyline class="dg-series dg-series-pos" points="' + safe + '"/>' +
        '<polyline class="dg-series dg-series-neg" points="' + wipe + '"/>' +
        '<circle class="dg-dot-pos" cx="300" cy="34" r="4"/>' +
        '<circle class="dg-dot-neg" cx="300" cy="122" r="4"/>' +
        txt(36, 48, "risking 1%", "dg-key dg-key-pos") +
        txt(36, 62, "90% left", "dg-note") +
        txt(180, 100, "risking 20%", "dg-key dg-key-neg") +
        txt(180, 114, "11% left", "dg-note") +
        txt(24, 150, "ten losing trades in a row", "dg-note"),
        "Two account curves over ten consecutive losses: risking 1% per trade leaves 90%, risking 20% leaves 11%."
      );
    },

    // Both exits, decided before the entry.
    stopAndTarget: function () {
      return svg(320, 168,
        frame(8, 18, 232, 122) +
        dash(12, 40, 236, 40) + dash(12, 122, 236, 122) +
        '<line class="dg-entry" x1="12" y1="84" x2="236" y2="84"/>' +
        '<polyline class="dg-series" points="20,84 46,74 72,92 98,66 124,80 150,54 176,64 202,44 228,50"/>' +
        txt(246, 44, "target", "dg-key dg-key-pos") +
        txt(246, 88, "entry", "dg-key") +
        txt(246, 126, "stop", "dg-key dg-key-neg") +
        txt(8, 158, "both decided before you enter", "dg-note"),
        "A price path between a green target line above, a grey entry line, and a red stop line below."
      );
    },

    // Higher highs and higher lows, plus a level price keeps stalling at.
    trendLevels: function () {
      return svg(320, 162,
        frame(8, 18, 302, 108) +
        dash(12, 46, 306, 46) +
        '<polyline class="dg-series" points="18,112 56,72 94,96 132,58 170,84 208,46 246,70 284,46"/>' +
        '<circle class="dg-mark" cx="132" cy="58" r="4"/>' +
        '<circle class="dg-mark" cx="208" cy="46" r="4"/>' +
        '<circle class="dg-mark-low" cx="94" cy="96" r="4"/>' +
        '<circle class="dg-mark-low" cx="170" cy="84" r="4"/>' +
        txt(112, 50, "stalls here twice", "dg-note") +
        txt(76, 112, "higher low", "dg-note") +
        txt(8, 150, "higher highs and higher lows — until they stop", "dg-note"),
        "A rising zigzag marking two higher highs and two higher lows against a dashed resistance level."
      );
    },

    // The gap you cross twice on every round trip.
    spread: function () {
      return svg(320, 150,
        '<rect class="dg-level" x="16" y="42" width="236" height="12" rx="3"/>' +
        '<rect class="dg-level" x="16" y="88" width="236" height="12" rx="3"/>' +
        bracket(262, 48, 94) +
        txt(16, 34, "ask — you buy here", "dg-key") +
        txt(16, 118, "bid — you sell here", "dg-key") +
        txt(278, 76, "spread", "dg-note") +
        txt(16, 140, "crossed once going in, once coming out", "dg-note"),
        "Two price levels, the ask above and the bid below, with the gap between them labelled spread."
      );
    },

    // Same percentage of the account, very different position sizes.
    volatility: function () {
      return svg(320, 152,
        '<rect class="dg-range dg-range-wide" x="120" y="30" width="190" height="14" rx="7"/>' +
        '<rect class="dg-range" x="170" y="72" width="90" height="14" rx="7"/>' +
        '<rect class="dg-range dg-range-tight" x="201" y="114" width="28" height="14" rx="7"/>' +
        '<line class="dg-mid" x1="215" y1="24" x2="215" y2="134"/>' +
        txt(8, 41, "Crypto", "dg-key") + txt(8, 83, "Stocks", "dg-key") +
        txt(8, 125, "Forex", "dg-key") +
        txt(64, 41, "~5%/day", "dg-note") + txt(64, 83, "~2%/day", "dg-note") +
        txt(64, 125, "~0.5%/day", "dg-note"),
        "Three horizontal bars showing a typical daily range: crypto widest, stocks narrower, forex narrowest."
      );
    },

    // Why a chart is easy in the middle and impossible at its edge.
    rightEdge: function () {
      return svg(320, 160,
        frame(8, 18, 302, 110) +
        '<rect class="dg-band" x="232" y="18" width="78" height="110"/>' +
        '<polyline class="dg-series" points="16,100 44,78 72,92 100,54 128,70 156,40 184,58 212,34 230,46"/>' +
        '<circle class="dg-mark" cx="100" cy="54" r="5"/>' +
        dashV(230, 18, 128) +
        txt(56, 44, "obvious now", "dg-note") +
        txt(250, 78, "?", "dg-query") +
        txt(8, 150, "you always decide at the right-hand edge", "dg-note"),
        "A chart with a clear pattern on the left, an obvious entry marked, and the right-hand portion blank."
      );
    }
  };

  // ---- Tiny SVG helpers -------------------------------------------------
  // Only what the diagrams above actually use. They exist so a diagram
  // reads as a list of shapes rather than a wall of attributes.

  function svg(w, h, body, description) {
    return '<svg class="tip-figure-svg" viewBox="0 0 ' + w + " " + h +
      '" role="img" aria-label="' + escapeAttr(description) + '">' + body + "</svg>";
  }

  function frame(x, y, w, h) {
    return '<rect class="dg-frame" x="' + x + '" y="' + y + '" width="' + w +
      '" height="' + h + '" rx="6"/>';
  }

  function dash(x1, y1, x2, y2) {
    return '<line class="dg-dash" x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '"/>';
  }

  function dashV(x, y1, y2) {
    return '<line class="dg-dash-strong" x1="' + x + '" y1="' + y1 + '" x2="' + x + '" y2="' + y2 + '"/>';
  }

  function txt(x, y, content, cls) {
    return '<text class="' + cls + '" x="' + x + '" y="' + y + '">' + escapeHtml(content) + "</text>";
  }

  function arrow(x1, y, x2, yEnd) {
    return '<line class="dg-arrow" x1="' + x1 + '" y1="' + y + '" x2="' + x2 + '" y2="' + yEnd + '"/>' +
      '<polygon class="dg-arrow-head" points="' + x2 + "," + (yEnd - 4) + " " + (x2 + 7) + "," + yEnd +
      " " + x2 + "," + (yEnd + 4) + '"/>';
  }

  // The square bracket that spans the two price levels in the spread diagram.
  function bracket(x, y, height) {
    return '<path class="dg-bracket" d="M' + x + " " + y + " h8 v" + height + " h-8" + '"/>';
  }

  // ---- Content ----------------------------------------------------------
  //
  // id       — what data-help points at, and the anchor in the list.
  // headline — one line for the strip. Must stand alone.
  // title    — the panel heading.
  // intro    — the lead paragraph, before the diagram.
  // visual   — key into DIAGRAMS, or omitted for tips a picture would not help.
  // sections — short titled blocks after the diagram, mirroring how the
  //            panel reads: one idea per heading.
  //
  // Inline markup in any body text: **bold**, {+green}, {-red}. See format().

  const CATEGORIES = [
    { key: "all", label: "All" },
    { key: "risk", label: "Risk" },
    { key: "charts", label: "Charts" },
    { key: "signals", label: "Signals" },
    { key: "mindset", label: "Mindset" },
    { key: "platform", label: "This app" }
  ];

  const TIPS = [
    {
      id: "risk-per-trade",
      category: "risk",
      title: "Risk a small, fixed slice per trade",
      headline: "Decide what a trade may cost you before you open it — not while it is losing.",
      intro: "Before you open a position, decide the most you are willing to lose on it. A common beginner's rule is **1–2% of the account** on any single trade.",
      visual: "riskDecay",
      sections: [
        {
          heading: "Why the number is small",
          body: "Ten losing trades in a row at 1% each still leaves about {+90% of the account}, and you can keep learning from the mistake. Ten in a row at 20% leaves {-11%}, and no later good decision can undo that. Losing streaks are normal; being unable to trade after one is not."
        },
        {
          heading: "Fix the amount first",
          body: "The risk comes first and the position size is worked out from it. Doing it the other way round — picking a size that feels right and discovering the risk afterwards — is how a bad week becomes a finished account."
        }
      ]
    },
    {
      id: "position-sizing",
      category: "risk",
      title: "Position size comes from your stop",
      headline: "Position size is a calculation, not a feeling: risk ÷ distance to your stop.",
      intro: "You know two numbers before you enter: how much you will risk, and how far away the price is that would prove you wrong. Those two give you the size — you never have to guess it.",
      visual: "stopAndTarget",
      sections: [
        {
          heading: "The arithmetic",
          body: "Risking $100, with a stop 5% below your entry: $100 ÷ 5% = **$2,000** of the instrument. Move the stop closer and the same $100 of risk buys a bigger position; move it further away and it buys a smaller one."
        },
        {
          heading: "Confidence is not an input",
          body: "Feeling especially sure is not a reason to size up. That feeling is strongest right before crowded, obvious trades — exactly the ones that tend to disappoint."
        }
      ]
    },
    {
      id: "stop-loss",
      category: "risk",
      title: "Know your exit before your entry",
      headline: "Write down both exits — wrong and right — before you enter.",
      intro: "Two prices, written down before you enter: the one that proves the idea wrong, and the one that means it worked. If you cannot name the first one, you do not have a trade, you have a hope.",
      visual: "stopAndTarget",
      sections: [
        {
          heading: "Why beforehand",
          body: "Deciding either price while the position is open is the moment fear and hope start trading for you. That is where beginners turn a small planned loss into a large unplanned one, by moving the stop 'just this once'."
        },
        {
          heading: "Both directions",
          body: "The {+target} matters as much as the {-stop}. Without one, a winning trade has no ending — you hold it until it turns, and then you are watching a loss instead of banking a gain."
        }
      ]
    },
    {
      id: "costs",
      category: "risk",
      title: "Costs come out of every trade",
      headline: "The price you see is not the price you get — costs land on every single trade.",
      intro: "Buying and selling happen at slightly different prices. You buy at the higher one and sell at the lower one, and the gap between them is called the **spread**.",
      visual: "spread",
      sections: [
        {
          heading: "Three costs, not one",
          body: "The spread, the broker's fee, and slippage — the last being the difference between where you clicked and where your order actually filled, which grows in a fast market."
        },
        {
          heading: "Why frequency matters",
          body: "A strategy that wins by a hair before costs loses after them. The more often you trade, the more of your result is decided by costs rather than by your ideas."
        }
      ]
    },
    {
      id: "candles",
      category: "charts",
      title: "How to read a candlestick",
      headline: "A candle's body is open-to-close; the thin wicks are the high and the low.",
      intro: "Each candle covers one slice of time and packs four prices into one shape. The thick **body** spans the opening and closing price; the thin **wicks** reach the highest and lowest price traded inside that slice.",
      visual: "candleAnatomy",
      sections: [
        {
          heading: "Reading the colour",
          body: "{+Green} means the candle closed above where it opened — buyers won that period. {-Red} means it closed below — sellers took over. The colour says nothing about whether the price is high or low, only about what happened inside that one slice."
        },
        {
          heading: "What a long wick tells you",
          body: "A long wick is a rejection: the price went there and did not stay. A body with almost no wicks means one side was in control from start to finish."
        },
        {
          heading: "On this chart",
          body: "Hover any candle to read its exact open, high, low and close. Switch to **Line** if you only care about closing prices — the line is this same data with the highs, lows and opens thrown away."
        }
      ]
    },
    {
      id: "timeframes",
      category: "charts",
      title: "Pick a timeframe and stay on it",
      headline: "1W is mostly noise, 1YR is mostly trend — the same move looks different on each.",
      intro: "A shorter range shows more detail and more noise; a longer one shows direction but hides the bumps. Both pictures below are the same instrument at the same moment.",
      visual: "timeframes",
      sections: [
        {
          heading: "The trap",
          body: "Changing range until you find the one that agrees with you is a well-known way to talk yourself into a trade. Choose the range that matches how long you intend to hold, and judge the trade on that one."
        },
        {
          heading: "It changes the signal too",
          body: "The signal badge is recalculated for whichever range you are viewing, so it can legitimately disagree with itself between 1W and 1YR. Neither answer is the wrong one — they are answers to different questions."
        }
      ]
    },
    {
      id: "trend",
      category: "charts",
      title: "Trend, support and resistance",
      headline: "Higher highs and higher lows is an uptrend — until it isn't.",
      intro: "An uptrend is a series of **higher highs and higher lows**; a downtrend is the opposite. Price often stalls around levels it has turned at before — support below, resistance above.",
      visual: "trendLevels",
      sections: [
        {
          heading: "Tendencies, not rules",
          body: "Nothing forces a level to hold. Levels break, and a level everyone can see is a level everyone is trading against. Treat them as places to pay attention, not as guarantees."
        },
        {
          heading: "Where beginners go wrong",
          body: "Drawing the line after the fact. If you need to nudge it to make it fit, it was not a level — it was a shape you wanted to see."
        }
      ]
    },
    {
      id: "volatility",
      category: "charts",
      title: "Not every instrument moves the same",
      headline: "The same % risk means a very different position size in crypto, forex and stocks.",
      intro: "How far an instrument typically travels in a day decides how much of it you can hold for a given amount of risk. These are rough, typical ranges — not promises.",
      visual: "volatility",
      sections: [
        {
          heading: "Three different animals",
          body: "Crypto can move several percent in a day and trades around the clock. Major forex pairs move a fraction of that. Individual stocks sit in between, trade only during market hours, and can **gap overnight** — opening far from where they closed, straight past any stop."
        },
        {
          heading: "What this means for size",
          body: "A position size copied from one market is wrong in another. Size against how much that instrument actually moves, not against a habit formed somewhere else."
        }
      ]
    },
    {
      id: "signals",
      category: "signals",
      title: "What the signal badge actually is",
      headline: "The badge is one moving-average crossover — an observation, not an instruction.",
      intro: "It compares two moving averages of the closing price: a **fast 10-candle** average against a **slow 20-candle** one. Recently crossed above reads BUY, recently crossed below reads SELL, otherwise HOLD. NONE means there was not enough history on that range to calculate it.",
      visual: "crossover",
      sections: [
        {
          heading: "It is always late",
          body: "Both averages are built from prices that have already happened, so a crossing confirms a move after it has started. The shaded gap in the picture is the part nobody can trade."
        },
        {
          heading: "Where it fails",
          body: "In a sideways market the two lines cross back and forth repeatedly and the badge is wrong most of the time. It is one indicator, and it knows nothing about why the price moved, what is scheduled tomorrow, or what you can afford to lose."
        }
      ]
    },
    {
      id: "past-performance",
      category: "signals",
      title: "Every pattern is obvious in hindsight",
      headline: "Patterns are obvious in the middle of a chart and invisible at its right edge.",
      intro: "Scroll to the middle of any chart and the right entry is plain to see. The right-hand edge — where you actually have to decide — never looks like that.",
      visual: "rightEdge",
      sections: [
        {
          heading: "Why the edge is different",
          body: "The bars that made the pattern obvious have not happened yet. Everything to the left of the line is certainty; everything you are paid for is to the right of it."
        },
        {
          heading: "The practical consequence",
          body: "A setup that looks flawless on history can feel impossible live. That gap is not a failure of nerve — it is the honest difference between reading and deciding."
        }
      ]
    },
    {
      id: "fomo",
      category: "mindset",
      title: "FOMO and revenge trading",
      headline: "Chasing a move you missed and doubling up after a loss are the two most expensive habits.",
      intro: "The two most expensive beginner habits are **chasing a move that already happened**, and **immediately sizing up to win back a loss**. Both replace a plan with a mood, and both tend to arrive right after a big candle.",
      sections: [
        {
          heading: "A check that works",
          body: "If you are placing a trade to feel better rather than because your plan called for it, close the tab. The market will still be here tomorrow, and so will your account if you leave it alone today."
        },
        {
          heading: "Why size creeps",
          body: "After a loss, the maths needed to get back to even gets worse the more you lost. Sizing up is the instinctive answer and the wrong one — it makes the next loss the one that matters."
        }
      ]
    },
    {
      id: "journal",
      category: "mindset",
      title: "Write down why you took the trade",
      headline: "One line before every trade: what you expect, why, and what would prove you wrong.",
      intro: "Before entering, write one line: what you expect to happen, why, and what would prove you wrong. It takes seconds, and it is the only record of what you were **actually** thinking rather than what you will later remember thinking.",
      sections: [
        {
          heading: "What it is for",
          body: "Reading a month of those lines back is the fastest way to find out whether you have an edge or just a habit. It also catches the trades you took out of boredom, which never feel like a category until you see five of them in a row."
        },
        {
          heading: "Keep it short",
          body: "A journal you do not write is worth nothing. One sentence that you actually record beats a template you abandon in a week."
        }
      ]
    },
    {
      id: "demo-first",
      category: "platform",
      title: "Practise here before risking real money",
      headline: "Demo Trading gives you $10,000 in virtual funds — trade it like it's real or it teaches you nothing.",
      intro: "Signing up gives you **$10,000 in virtual funds** on the Demo Trading page, with live BTC/USD prices.",
      sections: [
        {
          heading: "Use realistic sizes",
          body: "Practice at ten times the size you could really afford teaches the wrong instincts, and those are the instincts you bring with you when the money is real. Trade the demo as though the balance were yours."
        },
        {
          heading: "What a demo cannot teach",
          body: "It cannot reproduce how it feels to watch real money move. Everything else — the mechanics, the sizing, the discipline of writing down a plan — transfers completely."
        }
      ]
    },
    {
      id: "watchlist",
      category: "platform",
      title: "Follow a few instruments, not fifty",
      headline: "Learn how a handful of instruments behave instead of skimming dozens.",
      intro: "Save the few instruments you actually want to understand, and watch how they behave — how far they usually move in a day, how they react to news, when they go quiet.",
      sections: [
        {
          heading: "Why narrow beats broad",
          body: "Breadth is not an edge for a beginner. Knowing one market properly beats having an opinion about twenty, because the thing you are building is a sense of what is normal — and that only comes from repetition."
        },
        {
          heading: "On this page",
          body: "Sign in, then use the **+** on any search result or on the chart header to save it. The strip under the header is your list."
        }
      ]
    },
    {
      id: "not-advice",
      category: "platform",
      title: "This app does not tell you what to do",
      headline: "Everything here is educational — no part of it is financial advice.",
      intro: "Easy Trading shows prices, draws charts and calculates one textbook indicator. It does not know your finances, your timeline or your tolerance for losing money.",
      sections: [
        {
          heading: "What that means",
          body: "Nothing it displays can be a recommendation, because a recommendation requires knowing the person receiving it. The signal badge is a calculation, not an opinion about your situation."
        },
        {
          heading: "Including these tips",
          body: "Everything here is for learning. It is **not financial advice**."
        }
      ]
    }
  ];

  const BY_ID = {};
  TIPS.forEach(function (tip) { BY_ID[tip.id] = tip; });

  // ---- Text helpers -----------------------------------------------------

  function escapeHtml(text) {
    return String(text)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function escapeAttr(text) {
    return escapeHtml(text).replace(/"/g, "&quot;");
  }

  // The whole markup language: **bold**, {+green}, {-red}. Escaping happens
  // FIRST and the patterns are applied to the escaped text, so a tip can
  // never inject markup no matter what is written in the content above.
  function format(text) {
    return escapeHtml(text)
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\{\+([^}]+)\}/g, '<span class="tip-pos">$1</span>')
      .replace(/\{-([^}]+)\}/g, '<span class="tip-neg">$1</span>');
  }

  function categoryLabel(key) {
    for (let i = 0; i < CATEGORIES.length; i++) {
      if (CATEGORIES[i].key === key) return CATEGORIES[i].label;
    }
    return key;
  }

  // ---- The panel --------------------------------------------------------

  const sidebar = document.getElementById("tips-sidebar");
  const heading = document.getElementById("tips-sidebar-heading");
  const bodyEl = document.getElementById("tips-sidebar-body");
  const detailEl = document.getElementById("tips-detail");
  const browseEl = document.getElementById("tips-browse");
  const filterEl = document.getElementById("tips-filter");
  const listEl = document.getElementById("tips-list");
  const closeButton = document.getElementById("tips-close");

  let activeFilter = "all";
  let lastTrigger = null;

  function buildFilter() {
    CATEGORIES.forEach(function (cat) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "tips-filter-button" + (cat.key === "all" ? " is-active" : "");
      button.dataset.category = cat.key;
      button.textContent = cat.label;
      button.setAttribute("aria-pressed", cat.key === "all" ? "true" : "false");
      button.addEventListener("click", function () { setFilter(cat.key); });
      filterEl.appendChild(button);
    });
  }

  function setFilter(key) {
    activeFilter = key;
    Array.prototype.forEach.call(filterEl.children, function (button) {
      const on = button.dataset.category === key;
      button.classList.toggle("is-active", on);
      button.setAttribute("aria-pressed", on ? "true" : "false");
    });
    Array.prototype.forEach.call(listEl.children, function (row) {
      row.hidden = key !== "all" && row.dataset.category !== key;
    });
  }

  function buildList() {
    TIPS.forEach(function (tip) {
      const row = document.createElement("button");
      row.type = "button";
      row.className = "tips-row";
      row.dataset.category = tip.category;
      row.dataset.tipId = tip.id;

      const tag = document.createElement("span");
      tag.className = "tips-row-tag";
      tag.dataset.category = tip.category;
      tag.textContent = categoryLabel(tip.category);

      const title = document.createElement("span");
      title.className = "tips-row-title";
      title.textContent = tip.title;

      const blurb = document.createElement("span");
      blurb.className = "tips-row-blurb";
      blurb.textContent = tip.headline;

      row.appendChild(tag);
      row.appendChild(title);
      row.appendChild(blurb);
      row.addEventListener("click", function () { showDetail(tip.id); });
      listEl.appendChild(row);
    });
  }

  // One tip, laid out the way the panel reads top to bottom: category tag,
  // lead paragraph, the picture, then the short titled sections.
  function renderDetail(tip) {
    let html =
      '<button type="button" class="tips-back" data-tips-back>&larr; All tips</button>' +
      '<span class="tips-badge" data-category="' + escapeAttr(tip.category) + '">' +
      escapeHtml(categoryLabel(tip.category)) + "</span>" +
      '<p class="tips-intro">' + format(tip.intro) + "</p>";

    if (tip.visual && DIAGRAMS[tip.visual]) {
      html += '<figure class="tip-figure">' + DIAGRAMS[tip.visual]() + "</figure>";
    }

    tip.sections.forEach(function (section) {
      html += '<h4 class="tips-section-heading">' + escapeHtml(section.heading) + "</h4>" +
        '<p class="tip-text">' + format(section.body) + "</p>";
    });

    detailEl.innerHTML = html;
  }

  function showDetail(id) {
    const tip = BY_ID[id];
    if (!tip) return showBrowse();
    renderDetail(tip);
    heading.textContent = tip.title;
    detailEl.hidden = false;
    browseEl.hidden = true;
    bodyEl.scrollTop = 0;
  }

  function showBrowse() {
    heading.textContent = "Tips & tricks";
    detailEl.hidden = true;
    browseEl.hidden = false;
    bodyEl.scrollTop = 0;
  }

  // A side panel, so opening it does NOT take the page away: no showModal,
  // no backdrop, no focus trap, and clicking outside does not close it. The
  // chart stays usable while you read about it, which is the reason this is
  // a panel and not a dialog.
  function openSidebar(id, trigger) {
    if (!sidebar) return;
    if (trigger) lastTrigger = trigger;

    if (id && BY_ID[id]) showDetail(id);
    else showBrowse();

    sidebar.classList.add("is-open");
    document.body.classList.add("tips-open");
    syncTriggers(true);
    bodyEl.focus();
  }

  function closeSidebar(restoreFocus) {
    if (!sidebar || !sidebar.classList.contains("is-open")) return;
    sidebar.classList.remove("is-open");
    document.body.classList.remove("tips-open");
    syncTriggers(false);
    if (restoreFocus && lastTrigger) lastTrigger.focus();
    lastTrigger = null;
  }

  // Every "?" reports whether the panel it controls is showing. Cheap to do
  // for all of them at once, and it keeps the dots from disagreeing with
  // each other when one opens the panel and another closes it.
  function syncTriggers(open) {
    document.querySelectorAll("[data-help]").forEach(function (el) {
      el.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  if (sidebar) {
    buildFilter();
    buildList();
    showBrowse();

    if (closeButton) {
      closeButton.addEventListener("click", function () { closeSidebar(true); });
    }

    // The back link is rebuilt with every detail render, so it is delegated
    // rather than bound.
    detailEl.addEventListener("click", function (event) {
      if (event.target.closest("[data-tips-back]")) showBrowse();
    });
  }

  // ---- The strip --------------------------------------------------------

  const STORE_KEY = "easytrading.tips.collapsed";

  function readCollapsed() {
    try { return window.localStorage.getItem(STORE_KEY) === "1"; } catch (e) { return false; }
  }

  function writeCollapsed(collapsed) {
    try { window.localStorage.setItem(STORE_KEY, collapsed ? "1" : "0"); } catch (e) { /* storage off */ }
  }

  const strip = document.getElementById("tips-strip");
  const stripText = document.getElementById("tips-current");
  const stripPrev = document.getElementById("tips-prev");
  const stripNext = document.getElementById("tips-next");
  const stripToggle = document.getElementById("tips-toggle");

  // Starts on a different tip each day instead of always tip #1, and
  // deliberately does not auto-advance: text that changes under you while
  // you are reading it is worse than no text.
  let stripIndex = Math.floor(Date.now() / 86400000) % TIPS.length;

  function renderStrip() {
    if (!stripText) return;
    const tip = TIPS[stripIndex];
    stripText.textContent = tip.headline;
    // data-help rather than a private listener: the headline is a trigger
    // like every other "?", so it goes through the one delegated handler and
    // picks up the open/close toggling and aria-expanded syncing for free.
    stripText.dataset.help = tip.id;
    stripText.title = tip.title + " — click to read more";
  }

  function setCollapsed(collapsed, persist) {
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

    if (stripPrev) stripPrev.addEventListener("click", function () {
      stripIndex = (stripIndex - 1 + TIPS.length) % TIPS.length;
      renderStrip();
    });
    if (stripNext) stripNext.addEventListener("click", function () {
      stripIndex = (stripIndex + 1) % TIPS.length;
      renderStrip();
    });
    if (stripToggle) stripToggle.addEventListener("click", function () {
      setCollapsed(!strip.classList.contains("is-collapsed"), true);
    });
  }

  // ---- Triggers ---------------------------------------------------------
  //
  // One delegated listener rather than a listener per dot, so markup added
  // later is wired up automatically.

  document.addEventListener("click", function (event) {
    const target = event.target;
    const trigger = target && target.closest ? target.closest("[data-help]") : null;
    if (!trigger) return;

    event.preventDefault();
    const id = trigger.dataset.help;
    const open = sidebar && sidebar.classList.contains("is-open");

    // data-help="all" is the header "?" — the browsable list, not one topic.
    // Clicking the same "?" that opened the panel closes it again; clicking
    // a different one swaps the panel to that topic rather than closing.
    if (open && trigger === lastTrigger) {
      closeSidebar(true);
      return;
    }
    openSidebar(id === "all" ? null : id, trigger);
  });

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") closeSidebar(true);
  });
})();
