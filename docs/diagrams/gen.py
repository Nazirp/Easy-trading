# -*- coding: utf-8 -*-
"""Easy Trading — MS3/MS4 diagrams, redrawn from the code (2026-08-31)."""
import math, os

OUT = os.path.dirname(os.path.abspath(__file__))

INK, MUTE, LINE = "#1f2933", "#5c6b7a", "#8fa1b3"
NEUTRAL = ("#f5f7fa", "#aebdcc")
UI      = ("#f7efff", "#9a6ec9")
REST    = ("#eef4ff", "#5b86d6")
LOGIC   = ("#e6f0ff", "#3d6fc4")
PERSIST = ("#e9f7ef", "#3f9c6b")
EXTERN  = ("#fff3e3", "#d3902f")
GHOST   = ("#fafbfc", "#c2ccd6")
FONT = "'Helvetica Neue',Helvetica,Arial,sans-serif"
MONO = "'SFMono-Regular',Consolas,'Liberation Mono',monospace"


def head(w, h):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" font-family="{FONT}">
<defs>
<marker id="arw" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto">
  <path d="M0,0 L10,4 L0,8 z" fill="{LINE}"/></marker>
<marker id="arwo" markerWidth="11" markerHeight="9" refX="10" refY="4.5" orient="auto">
  <path d="M0,0 L11,4.5 L0,9" fill="none" stroke="{LINE}" stroke-width="1.4"/></marker>
<marker id="arwk" markerWidth="11" markerHeight="9" refX="10" refY="4.5" orient="auto">
  <path d="M0,0 L11,4.5 L0,9" fill="none" stroke="{INK}" stroke-width="1.4"/></marker>
<marker id="arwp" markerWidth="11" markerHeight="9" refX="10" refY="4.5" orient="auto">
  <path d="M0,0 L11,4.5 L0,9" fill="none" stroke="#9a6ec9" stroke-width="1.5"/></marker>
<marker id="arwi" markerWidth="11" markerHeight="9" refX="10" refY="4.5" orient="auto">
  <path d="M0,0 L11,4.5 L0,9" fill="none" stroke="#3f9c6b" stroke-width="1.5"/></marker>
</defs>
<rect width="{w}" height="{h}" fill="#ffffff"/>'''


def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def txt(x, y, s, size=13, fill=INK, anchor="middle", weight="normal", mono=False, style=""):
    s = esc(s)
    fam = f' font-family="{MONO}"' if mono else ""
    return (f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}" text-anchor="{anchor}" '
            f'font-weight="{weight}"{fam} font-style="{style or "normal"}">{s}</text>')


def box(x, y, w, h, title, lines=(), pal=NEUTRAL, dashed=False, r=7, tsize=15, lsize=12, mono_lines=True,
        top_title=False):
    f, s = pal
    d = ' stroke-dasharray="6 4"' if dashed else ""
    o = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{f}" stroke="{s}" stroke-width="1.6"{d}/>']
    if lines or top_title:
        ty = y + 26
    else:
        ty = y + h / 2 + 5
    o.append(txt(x + w / 2, ty, title, tsize, INK, weight="600"))
    for i, ln in enumerate(lines):
        o.append(txt(x + w / 2, ty + 19 + i * 16, ln, lsize, MUTE, mono=mono_lines))
    return "".join(o)


def edge(x1, y1, x2, y2, dash=False, marker="arw", col=LINE, w=1.5):
    d = ' stroke-dasharray="6 5"' if dash else ""
    return (f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="{col}" '
            f'stroke-width="{w}"{d} marker-end="url(#{marker})"/>')


def ell_pt(cx, cy, rx, ry, tx, ty):
    t = math.atan2((ty - cy) / ry, (tx - cx) / rx)
    return cx + rx * math.cos(t), cy + ry * math.sin(t)


def actor(x, y, label, sub=""):
    o = [f'<g stroke="{INK}" stroke-width="1.7" fill="none">',
         f'<circle cx="{x}" cy="{y-26}" r="11"/>',
         f'<line x1="{x}" y1="{y-15}" x2="{x}" y2="{y+12}"/>',
         f'<line x1="{x-16}" y1="{y-6}" x2="{x+16}" y2="{y-6}"/>',
         f'<line x1="{x}" y1="{y+12}" x2="{x-13}" y2="{y+33}"/>',
         f'<line x1="{x}" y1="{y+12}" x2="{x+13}" y2="{y+33}"/></g>',
         txt(x, y + 52, label, 13, INK, weight="600")]
    if sub:
        o.append(txt(x, y + 68, sub, 11, MUTE))
    return "".join(o)


def sysbox(x, y, w, h, label):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="none" '
            f'stroke="{LINE}" stroke-width="1.8"/>' + txt(x + w / 2, y + 26, label, 15, MUTE, weight="600"))


def note(x, y, w, h, lines, size=11):
    o = [f'<path d="M{x},{y} h{w-14} l14,14 v{h-14} h{-w} v{-h} z" fill="#fffdf2" stroke="#d9c98a" stroke-width="1.2"/>',
         f'<path d="M{x+w-14},{y} v14 h14" fill="none" stroke="#d9c98a" stroke-width="1.2"/>']
    for i, ln in enumerate(lines):
        o.append(txt(x + 11, y + 21 + i * 15, ln, size, "#7a6a2e", anchor="start"))
    return "".join(o)


def footer(w, h, text):
    return txt(w / 2, h - 16, text, 11, MUTE, style="italic")


# ─────────────────────────────────────────────────────────── 1. USE CASE
def use_case():
    W, H = 1520, 1110
    o = [head(W, H), txt(W / 2, 42, "Easy Trading — Use Case Diagram", 24, INK, weight="700")]
    o.append(txt(W / 2, 64, "redrawn from the use cases and the code, 2026-08-31", 12, MUTE, style="italic"))

    BX, BY, BW, BH = 300, 90, 830, 900
    o.append(sysbox(BX, BY, BW, BH, "Easy Trading"))

    o.append(actor(150, 470, "Beginner Trader", "(primary actor)"))

    RX, RY = 128, 42
    ucs = {
        "uc1": (525, 190, "UC01  Search Instrument"),
        "uc2": (525, 350, "UC02  View Price Chart", "with Signal"),
        "uc3": (525, 510, "UC03  Manage Watchlist"),
        "uc4": (525, 670, "UC04  Demo Trading", "with Live Chart"),
        "uc5": (525, 830, "UC05  Trading Journal"),
        "uc6": (935, 190, "UC06  Description", "(plain-language)"),
        "log": (935, 880, "Register / Log in", "SCRUM-39"),
    }
    for k, v in ucs.items():
        cx, cy, t1 = v[0], v[1], v[2]
        t2 = v[3] if len(v) > 3 else ""
        o.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{RX}" ry="{RY}" fill="{REST[0]}" stroke="{REST[1]}" stroke-width="1.6"/>')
        if t2:
            o.append(txt(cx, cy - 3, t1, 12.5, INK, weight="600"))
            o.append(txt(cx, cy + 14, t2, 12.5, INK, weight="600"))
        else:
            o.append(txt(cx, cy + 5, t1, 12.5, INK, weight="600"))

    # primary actor associations
    for k in ("uc1", "uc2", "uc3", "uc4", "uc5", "uc6"):
        cx, cy = ucs[k][0], ucs[k][1]
        px, py = ell_pt(cx, cy, RX, RY, 175, 470)
        o.append(f'<line x1="185" y1="470" x2="{px:.1f}" y2="{py:.1f}" stroke="{LINE}" stroke-width="1.4"/>')

    # secondary actors
    o.append(actor(1300, 300, "Twelve Data API", "candles (UC02)"))
    o.append(actor(1300, 560, "PostgreSQL", "application database"))
    o.append(actor(1300, 810, "Finnhub API", "live price (UC04)"))

    def link(k, ax, ay, dash=False):
        cx, cy = ucs[k][0], ucs[k][1]
        px, py = ell_pt(cx, cy, RX, RY, ax, ay)
        da = ' stroke-dasharray="5 4"' if dash else ''
        return f'<line x1="{px:.1f}" y1="{py:.1f}" x2="{ax}" y2="{ay}" stroke="{LINE}" stroke-width="1.4"{da}/>'

    o.append(link("uc2", 1265, 300))                       # Twelve Data
    for k in ("uc1", "uc2", "uc3", "uc4", "uc5"):          # database
        o.append(link(k, 1262, 560))
    o.append(link("uc4", 1265, 810))                       # Finnhub

    # «extend» — UC06 extends every user-facing use case (app-wide)
    EXT = "#9a6ec9"
    for k in ("uc1", "uc2", "uc3", "uc4", "uc5"):
        sx, sy = ell_pt(935, 190, RX, RY, ucs[k][0], ucs[k][1])
        ex, ey = ell_pt(ucs[k][0], ucs[k][1], RX, RY, 935, 190)
        o.append(edge(sx, sy, ex, ey, dash=True, marker="arwp", col=EXT, w=1.3))
    o.append(txt(935, 258, "«extend»  — app-wide", 11.5, EXT, style="italic", weight="600"))

    # «include» — the three user-scoped use cases need a session
    INC = "#3f9c6b"
    for k in ("uc3", "uc4", "uc5"):
        sx, sy = ell_pt(ucs[k][0], ucs[k][1], RX, RY, 935, 880)
        ex, ey = ell_pt(935, 880, RX, RY, ucs[k][0], ucs[k][1])
        o.append(edge(sx, sy, ex, ey, dash=True, marker="arwi", col=INC, w=1.3))
    o.append(txt(935, 812, "«include»", 11.5, INC, style="italic", weight="600"))

    o.append(note(300, 1006, 830, 82, [
        "UC06 has NO secondary actors — no external API and no database. It is static frontend content (no endpoint, no table),",
        "and it explains individual terms wherever they appear, so it extends every use case, not only UC02.",
        "UC01 Search reads the database only; the Twelve Data call lives in UC02.",
        "Solid = association.   Purple dashed = «extend».   Green dashed = «include».",
    ]))
    o.append(txt(1160, 1080, "Supersedes IMG_2396.png · scope: forex / crypto / stock", 11, MUTE, anchor="start", style="italic"))
    o.append("</svg>")
    return "".join(o)


# ───────────────────────────────────────────────────── 2. LAYERED ARCHITECTURE
def layered():
    W, H = 1420, 1010
    o = [head(W, H), txt(W / 2, 42, "Easy Trading — Layered Architecture", 24, INK, weight="700")]
    o.append(txt(W / 2, 64, "redrawn from the code, 2026-08-31 — supersedes the 19 Aug diagram", 12, MUTE, style="italic"))

    LX, LW = 90, 800
    rows = [
        (100, 126, "Presentation layer  ·  static/", UI,
         ["index.html", "js/search.js", "css/style.css"],
         "Served by the backend itself — one origin, no separate frontend server."),
        (262, 126, "REST layer  ·  *Controller", REST,
         ["InstrumentController", "PriceController", "ApiExceptionHandler"],
         "Everything under /api/** is JSON. Never touches a Repository directly."),
        (424, 126, "Business logic layer  ·  *Service", LOGIC,
         ["InstrumentSearchService", "PriceService", "Interval (window + staleness)"],
         "Owns the candle window per interval and the cache-staleness rule."),
        (586, 126, "Persistence layer  ·  *Repository", PERSIST,
         ["InstrumentRepository", "PriceRepository", "Instrument / Price entities"],
         "Spring Data JPA. ddl-auto: validate — never creates or alters tables."),
    ]
    for y, h, title, pal, items, sub in rows:
        o.append(box(LX, y, LW, h, title, pal=pal, tsize=15, top_title=True))
        for i, it in enumerate(items):
            col = i % 3
            o.append(f'<rect x="{LX+28+col*258}" y="{y+48}" width="238" height="30" rx="5" fill="#ffffff" stroke="{pal[1]}" stroke-width="1.1"/>')
            o.append(txt(LX + 28 + col * 258 + 119, y + 67, it, 11, INK, mono=True))
        o.append(txt(LX + LW / 2, y + h - 12, sub, 11, MUTE, style="italic"))

    for y in (226, 388, 550):
        o.append(edge(LX + LW / 2, y, LX + LW / 2, y + 34))

    o.append(box(LX, 750, LW, 104, "PostgreSQL  ·  db/schema.sql", pal=PERSIST, top_title=True))
    o.append(txt(LX + LW / 2, 800, "instrument   ·   price_candle  (PK: symbol, interval, datetime)", 12, INK, mono=True))
    o.append(txt(LX + LW / 2, 828, "SQL functions in schema.sql are REFERENCE ONLY — the application never calls them.", 11, "#8a5a2b", style="italic"))
    o.append(edge(LX + LW / 2, 712, LX + LW / 2, 748))

    CX, CW = 950, 380
    o.append(box(CX, 424, CW, 138, "Integration layer  ·  *Client", pal=LOGIC, top_title=True))
    o.append(f'<rect x="{CX+22}" y="470" width="336" height="30" rx="5" fill="#ffffff" stroke="{LOGIC[1]}" stroke-width="1.1"/>')
    o.append(txt(CX + 190, 489, "MarketDataClient → TwelveDataMarketDataClient", 11, INK, mono=True))
    o.append(f'<rect x="{CX+22}" y="506" width="336" height="30" rx="5" fill="#ffffff" stroke="{LOGIC[1]}" stroke-width="1.1" stroke-dasharray="5 4"/>')
    o.append(txt(CX + 190, 525, "LivePriceClient  (paper contract, MS4)", 11, MUTE, mono=True))
    o.append(txt(CX + CW / 2, 550, "Called by the business logic layer, never by a Controller.", 11, MUTE, style="italic"))
    o.append(edge(LX + LW, 487, CX - 2, 487))

    o.append(box(CX, 630, 182, 118, "Twelve Data", pal=EXTERN, top_title=True, tsize=14))
    o.append(txt(CX + 91, 676, "/time_series", 11, INK, mono=True))
    o.append(txt(CX + 91, 700, "outputsize is always", 10.5, MUTE, style="italic"))
    o.append(txt(CX + 91, 716, "sent — default is 30", 10.5, MUTE, style="italic"))
    o.append(box(CX + 198, 630, 182, 118, "Finnhub  (MS4)", pal=EXTERN, dashed=True, top_title=True, tsize=14))
    o.append(txt(CX + 289, 676, "quote, every 5 s", 11, MUTE, mono=True))
    o.append(txt(CX + 289, 700, "one price point per", 10.5, MUTE, style="italic"))
    o.append(txt(CX + 289, 716, "poll, not a candle", 10.5, MUTE, style="italic"))
    o.append(edge(CX + 91, 564, CX + 91, 628))
    o.append(edge(CX + 289, 564, CX + 289, 628, dash=True))

    o.append(note(90, 886, 800, 82, [
        "Layer rule, enforced by naming rather than by folder structure: a Controller always goes through a Service, never",
        "straight to a Repository or a Client. Packages are organised by feature (instrument/, price/, marketdata/, liveprice/),",
        "so one use case lives in one folder — the layer a class belongs to is carried by its *Controller / *Service /",
        "*Repository / *Client suffix.",
    ]))
    o.append(footer(W, H, "Interfaces are named without an I-prefix: MarketDataClient, not IMarketDataClient. There is no ITradingService."))
    o.append("</svg>")
    return "".join(o)


# ─────────────────────────────────────────────────────── 3. COMPONENT DIAGRAM
def component():
    W, H = 1460, 1250
    o = [head(W, H), txt(W / 2, 42, "Easy Trading — Component Diagram", 24, INK, weight="700")]
    o.append(txt(W / 2, 64, "redrawn from the code, 2026-09-15 — supersedes the 31 Aug version "
                            "(auth, watchlist and the demo-trading backfill added)", 12, MUTE, style="italic"))

    def comp(x, y, w, h, name, lines, pal, dashed=False):
        s = box(x, y, w, h, name, lines, pal=pal, dashed=dashed, tsize=14, lsize=11)
        f, st = pal
        s += (f'<rect x="{x+w-34}" y="{y+12}" width="22" height="16" fill="#fff" stroke="{st}" stroke-width="1.2"/>'
              f'<rect x="{x+w-39}" y="{y+15}" width="10" height="4" fill="#fff" stroke="{st}" stroke-width="1.1"/>'
              f'<rect x="{x+w-39}" y="{y+22}" width="10" height="4" fill="#fff" stroke="{st}" stroke-width="1.1"/>')
        return s

    o.append(sysbox(60, 92, 900, 900, "Easy Trading application  (single Spring Boot deployment, one origin)"))

    o.append(comp(100, 140, 360, 152, "Frontend  ::  static/", [
        "search · chart · range switcher · watchlist",
        "login / signup dialog · session state",
        "demo trading page — BTC/USD, 5 s live (MS4)",
        "glossary + description text (UC06)"], UI))
    o.append(note(486, 140, 434, 152, [
        "UC06 Description is INSIDE the frontend component.",
        "No endpoint, no database table, no backend dependency —",
        "the frontend owns the set of terms it chooses to display,",
        "so a network round trip would be the wrong shape for a",
        "tooltip that has to appear instantly.",
        "",
        "The frontend never calls a Client directly: every arrow out",
        "of it goes through the REST API.",
    ]))

    o.append(comp(100, 340, 360, 128, "REST API", [
        "InstrumentController · PriceController",
        "AuthController · WatchlistController",
        "ApiExceptionHandler → ApiError"], REST))
    o.append(comp(560, 340, 360, 128, "Instrument Search", [
        "InstrumentSearchService",
        "database only — never calls Twelve Data"], LOGIC))

    o.append(comp(100, 516, 360, 128, "Price", [
        "PriceService · Interval · SignalService",
        "window per interval (84/180/180/52)",
        "staleness = 1.5 × candle length"], LOGIC))
    o.append(comp(560, 516, 360, 128, "Market Data Client", [
        "MarketDataClient (interface)",
        "TwelveDataMarketDataClient",
        "getCandles(symbol, interval, outputSize)",
        "+ 1min backfill for the live chart (MS4)"], LOGIC))

    o.append(comp(100, 692, 360, 120, "Persistence", [
        "InstrumentRepository · PriceRepository",
        "UserRepository · WatchlistRepository",
        "Instrument · Price · User · WatchlistEntry"], PERSIST))
    o.append(comp(560, 692, 360, 120, "Accounts & Watchlist", [
        "AuthService (BCrypt) · SessionUser",
        "WatchlistService",
        "user id always from the session, never the caller"], LOGIC))

    o.append(comp(560, 860, 360, 104, "Live Price Client   (MS4)", [
        "LivePriceClient — paper contract, no impl yet",
        "4 s server-side quote cache when built"], GHOST, dashed=True))

    o.append(edge(280, 292, 280, 338))          # frontend -> REST
    o.append(edge(280, 468, 280, 514))          # REST -> Price
    o.append(edge(460, 404, 558, 404))          # REST -> Instrument Search
    o.append(edge(460, 440, 558, 720))          # REST -> Accounts & Watchlist
    o.append(edge(460, 580, 558, 580))          # Price -> Market Data Client
    o.append(edge(280, 644, 280, 690))          # Price -> Persistence
    o.append(edge(560, 404, 464, 700))          # Instrument Search -> Persistence
    o.append(edge(558, 760, 462, 760))          # Accounts & Watchlist -> Persistence

    o.append(box(1010, 516, 380, 128, "Twelve Data API", [
        "/time_series", "candles, on a cache miss or a stale cache",
        "1min series for the demo backfill (MS4)"], pal=EXTERN, top_title=True))
    o.append(box(1010, 860, 380, 104, "Finnhub API   (MS4)", [
        "quote endpoint, 5 s polling",
        "one price point per poll, not a candle"], pal=EXTERN, dashed=True, top_title=True))
    o.append(edge(920, 580, 1008, 580))
    o.append(edge(920, 912, 1008, 912, dash=True))

    o.append(box(100, 1020, 360, 96, "PostgreSQL", [
        "instrument · price_candle · app_user · watchlist",
        "SQL functions = reference only"], pal=PERSIST, top_title=True))
    o.append(edge(280, 812, 280, 1018))

    o.append(note(560, 1020, 830, 200, [
        "The signal is not a component. It is a field inside the /api/getPrice response (SignalResponse), so the frontend",
        "structurally cannot render a chart without its signal (UC02 BR1). Computed since SCRUM-64 — SMA 10 vs SMA 20,",
        "crossover within a 3-candle look-back; NONE below 21 candles.",
        "",
        "The demo-trading chart has TWO sources on one line (UC04 BR7): Twelve Data 1min closes for the past, Finnhub",
        "5 s quotes for the present. So the demo page depends on the Market Data Client as well as the Live Price Client —",
        "via the REST API, like everything else. The backfill is display-only and is never written to price_candle.",
        "",
        "No arrow from Instrument Search to the Market Data Client: /api/search reads the database only.",
        "Dashed = defined but not implemented in this milestone.",
    ]))
    o.append("</svg>")
    return "".join(o)


# ───────────────────────────────────────────────────── 4. SEQUENCE DIAGRAM
def sequence():
    W, H = 1560, 1120
    o = [head(W, H), txt(W / 2, 40, "Easy Trading — Sequence: GET /api/getPrice", 24, INK, weight="700")]
    o.append(txt(W / 2, 62, "UC02, drawn from PriceService.getPrices() — 2026-08-31", 12, MUTE, style="italic"))

    lanes = [(110, "Beginner\nTrader", UI), (300, "Frontend\nsearch.js", UI), (520, "PriceController", REST),
             (760, "PriceService", LOGIC), (990, "PriceRepository", PERSIST),
             (1210, "MarketDataClient", LOGIC), (1430, "Twelve Data", EXTERN)]
    TOP, BOT = 100, 1040
    for x, name, pal in lanes:
        parts = name.split("\n")
        o.append(f'<rect x="{x-88}" y="{TOP}" width="176" height="{46 if len(parts)==1 else 56}" rx="6" fill="{pal[0]}" stroke="{pal[1]}" stroke-width="1.5"/>')
        for i, p in enumerate(parts):
            o.append(txt(x, TOP + (30 if len(parts) == 1 else 24) + i * 16, p, 12.5, INK, weight="600", mono=(len(parts) > 1 and i == 1)))
        o.append(f'<line x1="{x}" y1="{TOP+58}" x2="{x}" y2="{BOT}" stroke="{LINE}" stroke-width="1.1" stroke-dasharray="5 6"/>')

    X = {n: lanes[i][0] for i, n in enumerate(["user", "fe", "ctl", "svc", "repo", "cli", "td"])}

    def msg(y, a, b, label, dash=False, note_r=None):
        x1, x2 = X[a], X[b]
        s = edge(x1, y, x2, y, dash=dash, marker="arwk", col=INK, w=1.5)
        mid = (x1 + x2) / 2
        s += txt(mid, y - 8, label, 11.5, INK, mono=True)
        if note_r:
            s += txt(mid, y + 14, note_r, 10.5, MUTE, style="italic")
        return s

    def selfmsg(y, a, label, sub=""):
        x = X[a]
        s = (f'<path d="M{x},{y} h54 v26 h-54" fill="none" stroke="{INK}" stroke-width="1.4" marker-end="url(#arwk)"/>')
        s += txt(x + 62, y + 4, label, 11.5, INK, anchor="start", mono=True)
        if sub:
            s += txt(x + 62, y + 20, sub, 10.5, MUTE, anchor="start", style="italic")
        return s

    def frame(x, y, w, h, kind, cond, sub=""):
        s = (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="none" stroke="{LOGIC[1]}" stroke-width="1.3"/>'
             f'<path d="M{x},{y} h74 l0,20 l-12,10 H{x} z" fill="{LOGIC[0]}" stroke="{LOGIC[1]}" stroke-width="1.3"/>')
        s += txt(x + 30, y + 15, kind, 11.5, INK, anchor="start", weight="700")
        s += txt(x + 84, y + 16, cond, 11.5, INK, anchor="start")
        if sub:
            s += txt(x + 84, y + 31, sub, 10.5, MUTE, anchor="start", style="italic")
        return s

    o.append(msg(200, "user", "fe", "clicks range  1m"))
    o.append(msg(248, "fe", "ctl", "GET /api/getPrice?symbol=BTC/USD&interval=4h", note_r="interval alone identifies the range — no range or limit param"))
    o.append(msg(310, "ctl", "svc", "getPrices(symbol, \"4h\")"))
    o.append(selfmsg(340, "svc", "Interval.fromCode(\"4h\")", "unknown code → 400 INVALID_INTERVAL"))
    o.append(msg(406, "svc", "repo", "most recent 180 candles, newest first"))
    o.append(msg(452, "repo", "svc", "cached", dash=True))
    o.append(selfmsg(482, "svc", "needsIngestion(cached, interval)", "MISSING / INSUFFICIENT / OK"))

    o.append(frame(230, 540, 1290, 376, "alt", "[ OK — ≥ 2 candles and newest within 1.5 × candle length ]",
                   "1.5× rather than exactly one candle: a closed market would otherwise read as permanently stale"))
    o.append(msg(614, "svc", "ctl", "the 180-candle window, oldest first", dash=True))

    o.append(f'<line x1="230" y1="648" x2="1520" y2="648" stroke="{LOGIC[1]}" stroke-width="1.2" stroke-dasharray="7 5"/>')
    o.append(txt(246, 670, "else", 11.5, INK, anchor="start", weight="700"))
    o.append(txt(292, 670, "[ MISSING or INSUFFICIENT ]", 11.5, INK, anchor="start"))
    o.append(selfmsg(700, "svc", "instrument unknown?", "→ 404 NOT_FOUND"))
    o.append(msg(762, "svc", "cli", "getCandles(symbol, \"4h\", 200)", note_r="200 = 180 displayed + 20 warm-up candles for the signal"))
    o.append(msg(818, "cli", "td", "/time_series?…&outputsize=200", note_r="outputsize is always sent; omitted, Twelve Data returns only 30"))
    o.append(msg(866, "td", "cli", "candles", dash=True))
    o.append(msg(894, "svc", "repo", "save(...)  then re-read the window"))

    o.append(msg(956, "ctl", "fe", "PricesResponse { symbol, interval, prices[], signal }", dash=True,
                 note_r="signal.verdict is always NONE — computation is SCRUM-46, not built yet"))
    o.append(msg(1012, "fe", "user", "chart + neutral signal label", dash=True))

    o.append(note(60, 1052, 780, 54, [
        "PriceService never calls the SQL functions in db/schema.sql. The window and the staleness rule are",
        "implemented in Java (Interval, needsIngestion); the SQL is kept as reference only.",
    ]))
    o.append("</svg>")
    return "".join(o)


for name, fn in [("use-case-diagram", use_case), ("layered-architecture", layered),
                 ("component-diagram", component), ("sequence-getprice", sequence)]:
    p = os.path.join(OUT, name + ".svg")
    open(p, "w", encoding="utf-8").write(fn())
    print("wrote", p)
