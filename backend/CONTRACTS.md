# Interface Contracts

**These endpoint paths and response shapes are final.**

`/api/search` reads the local DB only. It does not
call Twelve Data. `/api/getPrice` does call Twelve Data, but only when it has
no cached candles for that symbol+interval, or the ones it has are stale.

## 0. Deployment shape — one origin

This application serves both the JSON API and the frontend's HTML/JS/CSS. The
frontend's files live in `backend/src/main/resources/static/`; `WebConfig` maps the one
clean URL the frontend needs (`/demo-trading` → `demo-trading.html`).

Everything under `/api/**` is JSON; everything else is a page or an asset. That
split is what lets a page route be added without ever shadowing an endpoint.

The reason for one origin rather than a separate frontend server is
authentication: session cookies are origin-scoped, so a split origin would need
CORS on every endpoint, `credentials: 'include'` on every fetch, and
`SameSite=None; Secure` cookies — which requires HTTPS and is painful locally.
Same origin makes all of that unnecessary. Layer separation is
unaffected: the UI layer is still its own code calling the REST layer over HTTP.

## 1. REST API — frontend ↔ backend

### `GET /api/instruments`

The whole catalogue, for the search dropdown's "here is what exists" list —
so a first-time visitor is not asked to guess a symbol into an empty box.

**200 OK** — no parameters, no error cases.

```json
{
  "instruments": [
    { "symbol": "BTC/USD", "name": "Bitcoin / US Dollar", "type": "crypto",
      "lastPrice": 61060.22, "changePercent": -0.70 }
  ]
}
```

`lastPrice` and `changePercent` are **nullable**, and that is the contract.
They are read from candles already in `price_candle` at the `1day` interval and
from nowhere else — **this endpoint never calls Twelve Data.** An instrument
nobody has charted yet lists with a name and a type and `null` for both numbers;
the frontend shows the instrument type in that row instead of a price. Fetching
prices for every instrument so the list always looked complete would spend from
the 800/day budget every time somebody opened a dropdown.

`changePercent` is the move from the previous daily close to the latest one,
already in percent, rounded to 2dp — the frontend renders it, it does not
compute it.

Sorted by type, then symbol. No paging: the catalogue is six rows, and
`/api/search` remains the endpoint for matching text against the database.

Served by `PriceController`, not `InstrumentController`: `price` already depends
on `instrument`, so putting it the other way round would make the two packages
depend on each other. See `PriceService.browseInstruments()`.

### `GET /api/search?q={query}`

**200 OK** — always non-empty; no match is the 404 below.

```json
{
  "results": [
    { "symbol": "EUR/USD", "name": "Euro / US Dollar", "type": "forex" }
  ]
}
```

`type` is one of `"forex" | "crypto" | "stock"` — lowercase, matching
`instrument.type` in the DB.

**404 Not Found** — no instrument matches:

```json
{ "code": "NOT_FOUND", "message": "No instrument found for 'xyz'." }
```

This is not a failure: the frontend should render the
friendly empty state, not a raw error.

**400 Bad Request** — empty/missing query:

```json
{ "code": "INVALID_QUERY", "message": "Please enter an instrument symbol or name." }
```

### `GET /api/getPrice?symbol={symbol}&interval={interval}`

`interval` is one of `2h | 4h | 1day | 1week`, defaulting to `1day` if omitted.
Chart range → interval: `1w→2h`, `1m→4h`, `6m→1day`, `1yr→1week`.
Each range maps to its own interval, so `interval` alone identifies the range —
the number of candles to return is derived from the interval in the business
logic layer, not sent by the frontend. There is no `range`, `limit` or
date-range parameter and there will not be one.

Window per interval (`Interval.displayCandles()`), a **cap** rather than a
target — a thin market simply returns fewer:

| interval | range | candles returned | candles fetched |
|---|---|---|---|
| `2h`    | 1w  | 84  | 104 |
| `4h`    | 1m  | 180 | 200 |
| `1day`  | 6m  | 180 | 200 |
| `1week` | 1yr | 52  | 72  |

Fetched is always display **+ 20 warm-up candles**, so a moving average is
defined at the very first plotted point instead of starting partway across the
chart. The extra candles are persisted and simply fall outside the returned
window.

Served from the DB when candles are cached; otherwise ingested from Twelve Data
on the spot and persisted. Both look identical to the frontend.

**200 OK:**

```json
{
  "symbol": "EUR/USD",
  "interval": "1day",
  "prices": [
    { "datetime": "2026-08-22T00:00:00", "open": 1.0790, "high": 1.0820, "low": 1.0780, "close": 1.0810, "volume": null }
  ],
  "signal": { "verdict": "NONE", "label": "Not enough data yet for a signal", "explanation": null }
}
```

Field notes:

- **`datetime`, not `date`** — 2h and 4h candles carry a time of day; daily and weekly
  land on midnight. ISO-8601.
- **Numbers are real JSON numbers**, not strings. (Twelve Data returns strings;
  the backend converts.)
- **`prices` may be `[]`** — the instrument exists but the provider had nothing.
  Not an error.
- **`signal` is always present.** It ships in *this* response rather than from a
  separate endpoint, so the frontend structurally cannot render a chart without
  its signal. `verdict` is `BUY | SELL | HOLD | NONE`; `NONE` is the
  neutral "not enough data yet" state — still a 200, still render the
  chart. Computed (SMA 10 vs SMA 20 crossover within a
  3-candle look-back); `NONE` below 21 candles.

**404 Not Found** — unknown symbol, same signal as `/api/search`:

```json
{ "code": "NOT_FOUND", "message": "No instrument found for 'XYZ'." }
```

**400 Bad Request** — interval outside the supported four:

```json
{ "code": "INVALID_INTERVAL", "message": "Unknown interval 'banana'. Expected one of: 2h, 4h, 1day, 1week." }
```

### Authentication

Four endpoints. All of them speak the same `ApiError` shape on failure as the
two above.

**Credentials never travel in a URL.** Signup and login are POSTs with a JSON
body, because a query string is written to the server log, kept in browser
history and sent on in the `Referer` header.

```json
{ "username": "alice", "password": "correct-horse" }
```

Rules enforced in the business logic layer (`AuthService`), not by the database
and not by the browser: username trimmed, 3–50 characters; password at least 8
characters and at most 72 bytes (BCrypt reads no further than 72, so a longer
one is rejected rather than silently truncated).

#### `POST /api/signup`

**201 Created** — the account is created *and logged in*; no second login step.

```json
{ "username": "alice", "cashBalance": 10000.00000 }
```

A new account starts at the default virtual balance of $10,000.

**400 Bad Request** — `INVALID_REGISTRATION`, username or password fails the
rules above.
**409 Conflict** — `USERNAME_TAKEN`. Returned both when the name is already
taken and when a second registration wins the race to the `UNIQUE` constraint;
the frontend cannot tell the two apart and does not need to.

#### `POST /api/login`

**200 OK** — same body as signup.

**401 Unauthorized** — `INVALID_CREDENTIALS`:

```json
{ "code": "INVALID_CREDENTIALS", "message": "Username or password is incorrect." }
```

**Identical for an unknown username and for a wrong password** — same status,
same code, same message. Distinguishing them would turn the login form into a
way of discovering which accounts exist, so the frontend must not word its
message as though it knew which one it was.

#### `POST /api/logout`

**204 No Content**, always — including when nobody was logged in. "Log me out"
has no failure worth reporting.

#### `GET /api/me`

The current account, so the frontend can restore its logged-in state on page
load instead of trusting anything it kept client-side.

**200 OK** — same body as signup/login.
**401 Unauthorized** — `NOT_AUTHENTICATED`. This is the answer to "is anyone
logged in?", not an error: render the logged-out view, do not show an error.

#### Sessions

Login state is a server-side session; the browser holds only the standard
`JSESSIONID` cookie. `HttpOnly` (JavaScript cannot read it, so an XSS bug on any
page cannot steal the login), `SameSite=Lax`, 30-minute idle timeout, and the id
is rotated on every login so a pre-planted session id cannot become an
authenticated one. `Secure` is off because local development is plain HTTP —
turn it on wherever this is deployed over HTTPS.

Nothing extra is needed on the frontend for the cookie to work: same origin
(§0), so `fetch` sends it automatically.

#### What is *not* gated

`/api/search` and `/api/getPrice` stay fully public — an account is required only for user-scoped features. There is
no security filter chain in front of the application; endpoints that need a user
ask for one explicitly (`SessionUser.require`), which is why adding an endpoint
can never accidentally lock the public ones.

#### `INVALID_BODY`

**400** on any POST whose JSON body is missing or unparseable, in the API's own
error shape rather than Spring's default one.

### Watchlist

Three endpoints, all account-scoped. Every one of them answers
**401 `NOT_AUTHENTICATED`** when nobody is logged in — never a 500, and never an
empty list, because an empty list has to keep meaning "you have saved nothing".

#### `GET /api/watchlist`

**200 OK** — the current user's saved instruments, **oldest saved first**:

```json
{
  "items": [
    { "symbol": "EUR/USD", "name": "Euro / US Dollar", "type": "forex" }
  ]
}
```

Each item is the **same record `/api/search` returns** (`InstrumentMatchResponse`),
reused rather than copied, so one piece of frontend code renders a search result
and a watchlist row alike. No signal here — the signal belongs to the chart view
only.

`"items": []` is a normal 200: the account exists and has saved nothing.

#### `POST /api/watchlist`

Body `{ "symbol": "BTC/USD" }`.

**201 Created** — returns the saved instrument in the same item shape as above,
so the frontend can render the new row from the response instead of re-fetching
the list.

**404 `NOT_FOUND`** — no such instrument. A missing or blank `symbol` matches
nothing and gives the same 404.
**409 `ALREADY_ON_WATCHLIST`** — the user has already saved it. Show
it as information ("Already on your watchlist"), not as an error: what the user
wanted is already true.

#### `DELETE /api/watchlist?symbol={symbol}`

**204 No Content** — always, including for something that was never on the list.
Idempotent on purpose: the caller asked for it to be gone and it is gone, and a
double-clicked remove button must not look like a failure.

> **The symbol is a query parameter, not a path segment, and that is deliberate.**
> Symbols contain slashes (`BTC/USD`), so `/api/watchlist/BTC/USD` does not route
> at all, and an encoded `%2F` inside a path is rejected by Tomcat by default. A
> query string has neither problem. This looks less tidy than a path variable;
> do not "fix" it.

#### How the rules are enforced

The no-duplicates rule is the composite primary key `(user_id, symbol)` in
`db/schema.sql` — not a check in Java that someone has to remember. The service
*also* checks up front, but only to produce the message: two concurrent requests
can both pass that check, and the constraint is what actually stops the second
insert. The resulting `DataIntegrityViolationException` is translated to the same
409, so the race and the ordinary case are indistinguishable to the caller.

User scoping has exactly one enforcement point: the id comes from the session
(`SessionUser.require`) and is passed to the service, and there is a single
repository query that reads watchlist rows, filtered on it. No endpoint here
takes a user as a parameter, so no caller can name one.

### Demo trading — price feed

The endpoints the demo-trading chart is drawn from.

Demo trading is **BTC/USD only**. Every endpoint takes a `symbol`,
defaults it to `BTC/USD`, and **anything else is a 404 `NOT_FOUND`** —
deliberately not a silent redirect to BTC/USD, so a frontend bug is visible
rather than showing the wrong instrument's chart. All require a login.

#### `GET /api/getLiveChart?symbol={symbol}`

The **entire chart in one response**: Twelve Data's history and the live series
merged into a single list of 1-minute candles, plus the price readout. Polled
**once a second** for as long as the page is open and visible.

**200 OK**

```json
{
  "symbol": "BTC/USD",
  "candleSeconds": 60,
  "candles": [
    { "start": "2026-09-18T08:58:00Z", "open": 76310.00, "high": 76340.00,
      "low": 76301.20, "close": 76322.50, "live": false, "forming": false },
    { "start": "2026-09-18T08:59:00Z", "open": 76322.90, "high": 76402.10,
      "low": 76318.00, "close": 76386.01, "live": true,  "forming": false },
    { "start": "2026-09-18T09:00:00Z", "open": 76386.01, "high": 76391.00,
      "low": 76377.40, "close": 76388.20, "live": true,  "forming": true }
  ],
  "price": 76388.20,
  "priceAt": "2026-09-18T09:00:37.412Z",
  "outdated": false,
  "account": {
    "cash": 9810.00000,
    "margin": 190.00000,
    "equity": 10000.97050,
    "unrealisedPnl": 0.97050,
    "realisedPnl": 0.00000,
    "openTrades": [
      { "id": 14, "symbol": "BTC/USD", "direction": "LONG", "quantity": 0.00250000,
        "entryPrice": 76000.00000, "openedAt": "2026-09-18T08:41:12.118Z",
        "exitPrice": null, "closedAt": null, "pnl": 0.97050, "pnlPercent": 0.51 }
    ]
  }
}
```

**`account` is the caller's money and open trades, valued against `price`
above** — the same snapshot as the candles. That is why it lives in this response
rather than behind its own endpoint. Its fields are
described under *Simulated trading — the CFD model* below. It is never null.

`candles` is **oldest first, and at most one candle is `forming`** — always the
last. Its high, low and close keep moving until its minute ends, so it is redrawn
in place rather than appended. A new candle is appended only when one with a later
`start` appears. That forming candle is what makes the page feel alive between
minute boundaries: the price moves on every tick even though the candle does not
change.

**`start` and `priceAt` are real zoned instants** and must NOT have a `Z`
appended.
Different field names and different types on purpose, so neither rule has to be
remembered.

**`price` is the same number as the last candle's close.** It is stated separately
only so the readout does not have to reach into the array and handle it being
empty. Both come from one snapshot taken under one lock, so the number on the page
and the chart can never disagree.

**`live`** says which provider a candle came from: `true` for one aggregated here
from every trade on the Finnhub socket, `false` for one Twelve Data summarised.
Both are 1-minute candles on the same wall-clock grid, so they line up exactly —
but the two providers will not agree to the last decimal, and the frontend should
mark the join rather than letting a small step there read as market movement.

**`outdated: true` is a normal 200**, not an error: no trade has arrived recently,
so the socket has dropped, is reconnecting, or never started. Keep the chart on
screen and show a "may be outdated" note; do not blank it.

**An empty `candles` array is also a normal 200**: the server has just started and
Twelve Data is unavailable. Draw nothing and fill in as trades arrive. `price` and `priceAt` are then `null`.

`candleSeconds` is stated rather than assumed, so the bucket length stays a server
decision the frontend reads instead of a constant duplicated in two languages.

Rules behind it, for anyone changing the aggregation:

- **The bucket is one minute** — the finest interval any provider offers (Twelve
  Data's smallest is `1min`; TradingView's is the same). At one minute the
  backfilled half and the live half are the same resolution on the same grid, so
  the chart is one picture rather than two glued together.
- **Buckets align to the wall clock** (`floor(epochMillis / 60000)`), never to
  server start or to when a viewer connected — otherwise two people looking at the
  same market see differently-aligned candles, and neither would line up with
  Twelve Data.
- **A minute with no trades produces no candle at all** — a gap, not an invention.
  On a candle chart a missing candle is
  unremarkable, and a carried-forward one asserts the market did not move during a
  minute when the truth is that we were not listening.
- **A trade for an already-sealed minute is dropped**, because rewriting a candle
  a chart has already drawn changes history under the viewer.
- **The window is 30 minutes** (30 candles), held in memory. **No
  database**: `price_candle`'s `interval` CHECK does not allow `1min` and should
  not be widened — a 1-minute candle from forty minutes ago is outside the window,
  and nothing else in the application asks for that resolution. The only prices
  that must survive are the ones a simulated trade opened and closed at, and those
  are stored on the trade row.
- **The backfill is fetched once per process, not once per poll.** At one poll a
  second, refetching would be 3,600 Twelve Data calls an hour against a budget of
  **800 a day** — gone in about fourteen minutes. It can be held forever because
  it covers closed minutes in the past, which cannot change; they fall out of the
  rolling window on their own, and once the live series fills the window the
  backfill is not consulted at all. A *failed* fetch is not held: it is retried,
  but at most every 30 seconds, so an outage cannot turn a once-a-second poll into
  a once-a-second retry.
- **On the overlap, the live candle always wins.** The stream starts when the
  server starts, not when a page opens, so after half an hour of uptime the live
  series covers the whole window. The live candle saw every trade in its minute;
  the backfilled one is a provider's summary.

#### Simulated trading — the CFD model

**A trade is a position, not an execution.** It is opened `LONG` or `SHORT` at an
entry price and later closed at an exit price. A short is a first-class opening,
not the sale of something held, so buys and sells are never paired with each other
and every trade carries its own result.

**One formula** serves open and closed trades alike:

```
pnl        = max( (reference − entryPrice) × quantity × sign ,  −entryPrice × quantity )
sign       = +1 for LONG, −1 for SHORT
reference  = exitPrice when closed, otherwise the live price
pnlPercent = pnl / (entryPrice × quantity) × 100
```

**Opening reserves the margin**, `entryPrice × quantity`, out of `cash`; closing
returns margin + pnl. Leverage is 1:1. The `max` **caps a loss at the margin**: a
long can never lose more than that anyway, because the price floors at zero, and a
short only reaches it once the price has doubled from entry. So `cash` never goes
negative and the page never has to explain a debt — a short closed above twice its
entry simply reads −100%.

Closing is **all-or-nothing**. A user may hold longs and shorts at the same time;
nothing is netted.

**The client never sends a price — not to open, not to close.** The server fills at
its own last known price and ignores a `price` field if one is sent. A request body
is whatever the caller chooses to type, so a client-controlled price means
`{"price": 1}` buys a Bitcoin for a dollar; and a tab left open for five minutes
would post a five-minute-old price in perfect good faith.

##### The `trade` shape

```json
{ "id": 12, "symbol": "BTC/USD", "direction": "SHORT", "quantity": 0.00250000,
  "entryPrice": 76391.40000, "openedAt": "2026-09-29T10:14:07.221Z",
  "exitPrice": 75100.00000,  "closedAt": "2026-09-29T10:31:52.004Z",
  "pnl": 3.22850, "pnlPercent": 1.69 }
```

- `exitPrice` and `closedAt` are **both null while open and both set once closed**.
  The schema makes a half-closed trade unrepresentable.
- `pnl` / `pnlPercent` on a closed trade is its final result. On an open trade it is
  **null everywhere except `account.openTrades`**, where it is valued against the
  chart's price — see below for why. Null is not zero.
- `pnlPercent` is the return on the margin, to two places.
- `openedAt` and `closedAt` are **real zoned instants** — do not append a `Z`.

##### The `account` block

Returned by `getLiveChart` (example above) and by both POSTs below. **Never null**
for a logged-in user: someone who has never traded has their full cash, zero margin
and an empty `openTrades`.

| Field | Meaning |
| --- | --- |
| `cash` | free cash — what a new trade can use |
| `margin` | reserved by open trades, `Σ entryPrice × quantity` |
| `equity` | `cash + margin + unrealisedPnl` — what the account is worth right now |
| `unrealisedPnl` | sum of the open trades' `pnl`, against the chart's price |
| `realisedPnl` | sum of the closed trades' `pnl` |
| `openTrades` | open trades, oldest first, each with its live `pnl` |

`realisedPnl + unrealisedPnl` is the account's total P&L, so the page never needs
to know the starting balance.

**Open-trade P&L lives here and not in `GET /api/trades`**: it moves on every tick and must be valued against the same price the chart
beside it is drawing, from one snapshot. **When there are open trades and no live
price**, each open `pnl`, `unrealisedPnl` and `equity` are **null** — never a guess.
With nothing open they are exact either way.

#### `POST /api/trades` — open a trade

```json
{ "symbol": "BTC/USD", "direction": "LONG", "quantity": 0.0025 }
```

`direction` is `LONG` or `SHORT`, case-insensitive. `quantity` is a JSON number with
at most **8 decimal places** — more is rejected rather than rounded, because rounding
would open a different size than the one asked for and never say so.

**201 Created** — `{ "trade": { … }, "account": { … } }`. The trade is open.

**`trade.entryPrice` is the price it actually filled at**, which can differ slightly
from the number on screen when the button was pressed. Show *this* one in the
confirmation.

| Status | Code | When |
| --- | --- | --- |
| 400 | `INVALID_BODY` | quantity missing, zero, negative or over 8 decimals; direction not LONG/SHORT; unreadable JSON |
| 401 | `NOT_AUTHENTICATED` | no session |
| 404 | `NOT_FOUND` | any symbol but `BTC/USD` — checked before any provider is touched |
| 409 | `INSUFFICIENT_FUNDS` | the margin is more than the free cash |
| 503 | `LIVE_PRICE_UNAVAILABLE` | no current price — **refused, not filled at a stale one** |

Every failure writes **nothing**. The trade row and the cash movement are written in
one transaction, because one without the other is an account that does not add up
and cannot be repaired afterwards.

#### `POST /api/trades/{id}/close` — close a trade

No body. Closes the whole trade at the server's current price.

**200 OK** — `{ "trade": { … }, "account": { … } }`. The trade now has `exitPrice`,
`closedAt` and its final `pnl`.

**A double click closes once.** The close is a single
`UPDATE … WHERE closed_at IS NULL`, so a second request matches no row, credits
nothing, and answers 409 `TRADE_ALREADY_CLOSED`. Treat that 409 as "already done"
and refresh — it is not an error to show.

The id is a number in the path. The query-parameter rule applies to *symbols*,
because they contain slashes; it was never a rule against paths.

| Status | Code | When |
| --- | --- | --- |
| 401 | `NOT_AUTHENTICATED` | no session |
| 404 | `NOT_FOUND` | no such trade, **or someone else's** — the same answer for both, never 403 |
| 409 | `TRADE_ALREADY_CLOSED` | already closed, including by your own earlier click |
| 503 | `LIVE_PRICE_UNAVAILABLE` | no current price — the trade stays open |

#### `GET /api/trades?symbol={symbol}`

This user's trades for the instrument, **open and closed, newest first by
`openedAt`**. Open trades are included so the journal can link one; their `pnl` is
null here, because this endpoint never reads a live price. `symbol` defaults to
`BTC/USD`, and anything else is a 404.

```json
{ "symbol": "BTC/USD", "trades": [ { … }, { … } ] }
```

An empty array is a normal 200 — a user who has not traded yet is not an error.

> **If the page needs a number this endpoint
> does not send, add it here rather than deriving it there.**

#### Where the price comes from

`LivePriceService` holds **the last `LivePrice` and the `Instant` it arrived, in a
field**.

The field is fed by `FinnhubTradeStream` (§3), and a request makes **no
upstream call at all**.

The timestamp answers *"is the stream still
alive?"* — a price younger than `liveprice.max-price-age` means trades are
flowing; an older one means the socket has stalled, is reconnecting, or never
started, and that is when the REST quote is called as a fallback. If that fails
too, the held price goes out with `outdated: true`.

**Still a field, not a table.** The value is worthless seconds after it is
written, so persisting it would mean a row per trade that is never read again.
`price_candle` caches history because history is still true tomorrow; this is not.
A restart refills it from the stream within a second.

#### The backfill is never persisted

`LiveChartService` calls `MarketDataClient.getCandles(symbol, "1min", 30)`
**directly**, bypassing `PriceService` and the `Interval` enum, and injects no
repository at all. `1min` is not one of our four chart intervals, `price_candle`'s
`CHECK` constraint does not allow it, and 1-minute candles from an hour ago are
not "recent" in any sense the live chart means. These points are scenery for the
first half hour of a demo and are gone from the 30-minute window after that.

#### The Finnhub symbol is a constant

Finnhub spells BTC/USD `BINANCE:BTCUSDT`. That mapping lives in
`DemoInstrument.FINNHUB_SYMBOL`, next to the client.

### Trading journal

Four endpoints, all account-scoped, all **401 `NOT_AUTHENTICATED`** when nobody
is logged in.

**There is no price or signal on an entry.** A linked trade already records the
exact price and the exact instant, so a snapshot beside it would be a second
record of one moment — and where two records of one moment disagree, nothing can
say afterwards which was right. The accepted cost: an entry that names an
instrument but links no trade does not record what it was worth at the time.

#### `POST /api/journal`

Body `{ "body": "...", "symbol": "BTC/USD", "tradeId": 42 }` — `symbol` and
`tradeId` are both optional, and the common entry has neither.

**201 Created**:

```json
{
  "id": 17,
  "body": "Sized this one properly.",
  "symbol": "BTC/USD",
  "tradeId": 42,
  "createdAt": "2026-09-23T09:41:07Z",
  "updatedAt": null
}
```

`createdAt` and `updatedAt` are **real zoned instants** — do not append a `Z`.
Same rule as `openedAt` / `closedAt` on `/api/trades`; the opposite of `datetime` on
`/api/getPrice`, which is zone-less UTC and does need one.

`updatedAt` is **null until the entry has actually been edited**, and is not set
to `createdAt` on insert. That is what lets the page show "edited" from the
presence of the value alone.

> **When `tradeId` is sent, `symbol` is ignored and taken from the trade.** The
> trade already knows its instrument; accepting the caller's word for it would
> create a pair that can disagree. Same rule as the execution price coming from
> the server: anything the server can determine, the server determines.

**An entry carries the `symbol` and the `tradeId`, and nothing else about the
trade.** Side, quantity and price are not embedded: the page fetches
`GET /api/trades` once and joins on the id. That is the opposite choice to the
`account` block inside `/api/getLiveChart`, and the difference is the reason for
both — **P&L and the chart must describe the same instant, and a trade row never
changes.** Two calls can only disagree about something that moves.

**400 `INVALID_BODY`** — `body` missing, empty or only whitespace. The
stored text is trimmed.
**404 `NOT_FOUND`** — unknown `symbol`, **or** a `tradeId` that is not this
user's. See the note below on why that is a 404.

#### `GET /api/journal`

**200 OK** — this user's entries, **newest first**:

```json
{ "entries": [ { "id": 17, "body": "...", "symbol": null, "tradeId": null,
                 "createdAt": "2026-09-23T09:41:07Z", "updatedAt": null } ] }
```

`"entries": []` is a normal 200: the account exists and has written nothing.

There is deliberately **no `GET /api/journal/{id}`**. The list is the only read,
and the page already holds every entry it can display — an endpoint nothing calls
is an endpoint nothing checks.

#### `PATCH /api/journal/{id}`

Body `{ "body": "...", "tradeId": 42 }` — `tradeId` optional. **200 OK** with the
updated entry, `updatedAt` now set.

**A trade link can be added, never changed**. An entry written without
a trade can be linked to one on edit — the trade may not have been open yet when the
thought was written down — and `symbol` then comes from the trade, as on create.
An entry that already has a trade keeps it: it records what somebody thought about
that trade, and re-pointing it afterwards would rewrite that silently. Sending the
trade it already has is accepted as no change; omitting `tradeId` leaves the link as
it is. There is no field for the symbol and no way to remove a link. PATCH rather
than PUT because the body is not the whole resource.

**400 `INVALID_BODY`** — blank body. **404 `NOT_FOUND`** — the entry, or the
`tradeId`, is not this user's (the entry is checked first). **409 `ALREADY_LINKED`**
— the entry is linked to a different trade; nothing is written.

#### `DELETE /api/journal/{id}`

**204 No Content**. **404 `NOT_FOUND`** if the entry is not this user's or does
not exist.

> **Not idempotent, unlike `DELETE /api/watchlist`, and that is deliberate.** A
> watchlist row is named by something the user picked (`BTC/USD`), so answering
> 204 for one that is already gone tells the truth. An entry is named by an opaque
> id, so a 204 for an id that is not theirs would report a deletion that did not
> happen and the user would believe their writing was gone.

#### An id is guessable, so ownership is enforced in the query

Entry ids are small integers. Every read, edit and delete goes through
`findByIdAndUserId(id, userId)` — **never** `findById` followed by a comparison in
Java. The second shape reads someone else's writing into memory before deciding it
is not allowed, which is one careless log line away from leaking it, and it invites
a later refactor to drop the check. The same applies to the `tradeId` on a new
entry, which is resolved with `findByIdAndUserId` on the trade table.

**Everything that is not yours is a 404, never a 403**, and with the same `code`
and message as something that does not exist. A 403 confirms the id is real, which
is what walking an id space is looking for. This matters most for `tradeId`:
without it, posting entries with 1, 2, 3… and watching which are accepted would
report how many trades other people have placed. Same instinct as a failed login
not saying which half was wrong.

## 2. `MarketDataClient` — backend ↔ Twelve Data

```java
public interface MarketDataClient {
    List<Candle> getCandles(String symbol, String interval, int outputSize); // called by PriceService on a cache miss
}

public record Candle(LocalDateTime datetime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume);
```

`TwelveDataMarketDataClient.getCandles` calls
`GET /time_series?symbol={symbol}&interval={interval}&outputsize={n}&apikey={key}`
and maps the raw string-typed fields into `Candle`.

`outputsize` is always sent: omitting it makes Twelve Data return its default of
30 candles, short of every chart range. `n` is the interval's display
window **plus** a signal warm-up allowance, so the number fetched is deliberately
larger than the number returned to the frontend. Clamped to Twelve Data's max of
5000.

`timezone=UTC` is always sent as well. **Timezone convention — intraday values
are UTC, daily and weekly values are the exchange's trading date.** Twelve Data's
`timezone` parameter defaults to `Exchange` (local exchange time), so without it
each instrument's candles arrive on its own exchange clock and land in the
database as a naive `LocalDateTime` that means a different instant per symbol —
uncorrectable afterwards, because no single offset applies to all of them. Twelve
Data ignores the parameter for `1day`/`1week`, which is the behaviour we want: a
daily candle is a *trading day*, an exchange-local concept, and the frontend
renders those as a date with no time of day.

Consequently the frontend must parse a `2h`/`4h` `datetime` as UTC (append `Z`),
and render `1day`/`1week` as a plain calendar date without applying any offset. Twelve Data formats datetimes per
interval — `"2026-08-22"` for daily/weekly, `"2026-08-22 12:00:00"` for 2h/4h —
both normalized to `LocalDateTime` at the client boundary.

## 3. Backend ↔ Finnhub — the trade stream, and the REST quote behind it

**The live source is the WebSocket; the REST quote is the fallback.** That is the
one thing to know before changing anything here.

### The stream

`FinnhubTradeStream` opens `wss://ws.finnhub.io?token={key}` at startup and sends
`{"type":"subscribe","symbol":"BINANCE:BTCUSDT"}`. Every trade is parsed by
`FinnhubMessageParser` and pushed into `LivePriceService.acceptStreamedPrice`.

**One connection per server** — not per user, not per request. A price is a
property of the market, not of who is asking, so a hundred open pages cost one
subscription. This is also the first long-lived stateful thing in an otherwise
request/response application: it exists while nobody is asking for anything, can
fail while idle, and delivers data on a thread nobody called.

Message shapes:

```
{"type":"trade","data":[{"s":"BINANCE:BTCUSDT","p":76386.01,"t":1789480000123,"v":0.013}]}
{"type":"ping"}
{"type":"error","msg":"..."}
```

Four things that will bite whoever changes this:

- **`t` on the socket is in MILLISECONDS. `t` on the REST quote is in SECONDS.**
  Same provider, same field name, different unit. Read the socket's as seconds and
  every live point lands in 1970.
- **`java.net.http.WebSocket` delivers text in FRAGMENTS**, and sends nothing
  further until `request(1)` is called again. Miss the first and JSON corrupts
  under load; miss the second and the stream stops dead after one message and
  looks like a provider outage.
- **A ping, an error, or an unparseable frame is not a dead connection.** All
  return no trades rather than throwing — one malformed frame must never become a
  reconnect storm.
- **Reconnect backoff resets on a MESSAGE, not on a successful connect**
  (`ReconnectBackoff`: 1s, 2s, 4s, 8s, 16s, then 30s for ever). A socket that
  opens and immediately closes would otherwise reset the schedule every time and
  hammer the provider once a second for ever. Opening is not the same as working.

The stream **does not start** when `liveprice.finnhub.stream-enabled` is `false`
or no API key is set.

It also **does not push to the browser.** Twenty updates a second is more than a
chart can show and more than a poll needs.

### The REST quote

```java
public interface LivePriceClient {
    LivePrice getLivePrice(String finnhubSymbol);  // FinnhubLivePriceClient — cold-start seed and fallback
}

public record LivePrice(String symbol, BigDecimal price, Instant timestamp);
```

`FinnhubLivePriceClient` calls `GET /quote?symbol={finnhubSymbol}&token={key}` and
maps `c` (current price) and `t` (UNIX timestamp in **seconds**) into `LivePrice`.

**Kept deliberately.** It is the seed for the moment between startup and the first
trade, and the answer while the socket is reconnecting. Deleting it would turn a
dropped socket from a degradation into an outage, and would make the 503 path and
its tests dead code.

Two details:

- **The parameter is Finnhub's spelling, not ours.** Neither the client nor the
  stream translates; `LivePriceService` and `FinnhubTradeStream` pass
  `DemoInstrument.FINNHUB_SYMBOL`. What comes back carries *our* symbol
  (`BTC/USD`), so nothing downstream has to know the provider's naming.
- **A price of `0` is a failure, not a price.** Finnhub answers `200` with
  `{"c":0}` for a symbol it has no data on — there is no 404 — so both the client
  and the parser treat zero as a failed reading rather than plotting $0.

Both are mappers and nothing else: the held price, the fallback decision and the
login gate live above them, in `LivePriceService` and `LivePriceController`. Same
division `TwelveDataMarketDataClient` has with `PriceService`.

Two providers means **two `RestClient` beans** in the context
(`twelveDataRestClient`, `finnhubRestClient`), so both clients inject theirs by
`@Qualifier` rather than by type. Finnhub's bean carries connect/read timeouts,
because `LivePriceService` holds a lock across that call.

## 4. Persistence

Schema is `db/schema.sql`, which is the contract on the DB side. Entities map
1:1: `Instrument` → `instrument`, `Price` → `price_candle` (composite PK
`symbol, interval, datetime`), `User` → `app_user` (surrogate `id`, `username`
`UNIQUE`, `password_hash`, `cash_balance`), `WatchlistEntry` → `watchlist`
(composite PK `user_id, symbol`), `Trade` → `trade` (one row per position: open
until `exit_price` and `closed_at` are set, together), `JournalEntry` →
`journal_entry`. Hibernate runs with
`ddl-auto: validate` — it never creates or alters tables, only checks the
mapping against the applied schema.

**Not everything the API returns is stored, and one thing deliberately never is.**
The demo-trading backfill and the live quote
have no entity, no table and no repository between them —
see §1. `price_candle` holds the four chart intervals and nothing else; its
`CHECK` constraint enforces that.

**A trade row changes exactly once — when it closes.** Every other row in this
schema is written and never updated; a trade is updated a single time, by
`UPDATE trade … WHERE closed_at IS NULL`, so a double close writes nothing the second
time. Cash moves by a relative update (`cash_balance = cash_balance + :delta`, guarded
by `cash_balance + :delta >= 0`) rather than read-modify-write, so two concurrent
trades cannot lose one another's change.

**Passwords are hashed in Java and nowhere else.** `app_user` has a
`password_hash` column and no `password` column; BCrypt (`spring-security-crypto`,
not the full `spring-boot-starter-security`) hashes on registration and compares
on login, inside `AuthService`. No SQL statement in this application ever
receives a plaintext password.

`User.STARTING_CASH` and the `DEFAULT 10000.00` on the column state the same
rule twice; as everywhere else here, **the Java is authoritative** and the DB
default is a backstop for rows inserted by hand. `created_at` is the exception,
owned by the database (`DEFAULT NOW() AT TIME ZONE 'UTC'`) so every row lands on
one clock whatever the server's timezone.

`PriceService.needsIngestion()` classifies the cache MISSING / INSUFFICIENT / OK
in Java and re-ingests when the newest candle is older than **1.5x the candle
length** (`Interval.stalenessThreshold()`). 1.5x rather than exactly one candle
because a closed market otherwise reads as permanently stale: on a Sunday the
newest `2h` candle for a forex pair is legitimately hours old, and every
1w-range page load would fire an ingest that returns nothing new, against Twelve
Data's 800/day cap.

## 6. Where this was verified

Testcontainers-Postgres integration tests, plus plain unit tests where a
container would add nothing:

- `InstrumentSearchIntegrationTest` — a match is returned; no match gives
  `NOT_FOUND`; an empty query is rejected before the DB is touched.
- `PriceIntegrationTest` — cached candles are served with no WireMock call;
  unknown symbol gives `NOT_FOUND`; unknown interval gives `INVALID_INTERVAL`;
  and an instrument with no candles at the requested interval triggers a real
  call to a WireMock-stubbed Twelve Data, with the result both returned and
  persisted under the right interval. `2h` and `1week` ingest
  cases, an assertion that `outputsize` actually goes out on the request, and a
  case proving the returned series is capped at the interval's window and comes
  back oldest-first.
- `PriceServiceTest` — the MISSING / INSUFFICIENT / OK classification as a plain
  unit test, no Spring context and no database.
- `WatchlistIntegrationTest` — a new account's list is empty;
  adding puts the instrument on it and returns it; the list is oldest-first, not
  alphabetical; adding twice gives 409; an unknown symbol gives 404; removing is
  idempotent; a symbol containing a slash survives the DELETE round trip; two
  users can save the same instrument and neither sees the other's list; all
  three endpoints give 401 when logged out, and again after logout.
- `AuthIntegrationTest` — signup creates an account at $10,000 and
  stores a 60-character BCrypt hash rather than the password; a taken username
  gives 409; a short password gives 400 and writes no row; login then `/api/me`
  round-trips the session cookie; a wrong password and an unknown username fail
  *identically*; `/api/me` is 401 when logged out and again after logout; the
  session id changes on login (fixation); and `/api/search` and `/api/getPrice`
  still answer 404, not 401, with no account at all.
- `LivePriceServiceTest` — the held price and its fallback
  as a plain unit test: a hit inside the window, a refetch once too old, the
  stale-but-served path, the nothing-held error, recovery on the next good poll, a
  wrong symbol rejected before the provider is touched — and a
  streamed trade served with **no** upstream call, the newest trade winning, and a
  stalled stream falling through to the REST quote.
- `FinnhubMessageParserTest` — the wire format, no socket and no Spring
  context, against payloads captured from the live stream: `t` read as
  milliseconds (the REST quote's is seconds), a whole batch in order, a ping and
  an error yielding nothing, truncated and malformed JSON returning empty rather
  than throwing, zero-price and priceless entries dropped while good ones beside
  them survive, and another instrument's trade ignored.
- `LiveCandleAggregatorTest` — bucketing with
  no Spring, no network and no clock of its own, so "an hour of silence passed" is
  a value rather than an hour of waiting: OHLC from several trades in one minute,
  buckets aligned to the wall clock rather than to the first trade, a new minute
  sealing the previous one, a finished minute sealed even when no later trade ever
  arrives, **a silent minute producing no candle at all**, a long silence leaving
  the series short rather than inventing half an hour, the window bounded at its
  recent end, a late trade for a sealed minute dropped without making the feed look
  fresher than it is, and snapshot being idempotent — which matters because the
  page polls it once a second and snapshot seals as a side effect.
- `LiveChartServiceTest` — joining the two halves of the chart, with a
  hand-written `MarketDataClient` fake rather than a mock because the point of half
  these tests is to **count the calls**: history alone gives the backfill oldest
  first with nothing marked live; the readout is the last candle's close and, with
  no trades at all, is dated to the *end* of that minute rather than its start;
  twenty polls cost **one** Twelve Data request, and a failed fetch costs one too;
  a live candle replaces the backfilled one for the same minute; once the live
  series fills the window Twelve Data is not asked at all; the chart is trimmed to
  the window at its recent end; exactly one candle is ever marked forming; a dead
  provider gives an empty chart with a null price rather than an error; an empty
  answer is not held as if it were the history; and anything but `BTC/USD` is a 404
  before any provider is touched.
- `TradeTest` — the one formula with nothing around it: no Spring, no
  database, no mocks. A long profits on a rise and a short on a fall, by the same
  amount with opposite sign; the result at the entry price is exactly zero; a short
  at **exactly twice** its entry has lost exactly its margin, and one past it is
  **capped** there, so the credit on close is zero rather than negative; a long at a
  price of zero lands on the same cap, which is why it only ever bites on shorts; the
  margin is the full notional rounded once to five places; the percentage is the
  return on the margin and does not depend on size.
- `TradeServiceTest` — the rules around the formula, with Mockito and no
  container. Opening takes the margin as one relative update and writes an open
  trade; a short opens without holding anything; an unaffordable trade is 409 and
  **writes no row**, because the cash is taken first; the price is the server's, never
  the client's; malformed orders are 400 and a wrong symbol 404 **before any price is
  fetched**; closing credits margin + result; **the losing side of a double click is
  409 and credits nothing**; a closed trade, someone else's trade and a missing price
  each leave everything untouched; the account block is never null, its equity is
  cash + margin + live result, open trades come back oldest first, and with open
  trades but no price the numbers that need one are null rather than guessed. Several
  assertions check what was *not* called — "it threw" does not prove nothing was
  written or credited.
- `TradingIntegrationTest` — what a unit test cannot show, over real HTTP
  against a real Postgres: open then close moves the cash by exactly the result, read
  back from the database; a short profits when the price falls; the mapping matches
  the columns and eight decimals survive; a `price` in the body is ignored;
  **two simultaneous closes of one trade answer 200 and 409 and credit the account
  once** — a property of the database's row lock, which no mock can show; someone
  else's trade is 404 and stays open; the
  history carries results only for closed trades; the chart's account block is
  present before the first trade; and every endpoint is 401 when logged out.
- `JournalServiceTest` — the journal's rules with no Spring and no
  container: an entry with no symbol and no trade is the normal case; a blank body is
  rejected **before anything is looked up**, asserted by checking that the
  repositories were not touched at all rather than only that it threw; a linked trade
  decides the symbol and a `symbol` in the request is ignored; an edit changes the
  text and `updated_at` and leaves the links alone; an unlinked entry can be linked
  on edit, a linked one is refused with nothing written, and resending its own trade
  is no change; and every ownership rule is
  exercised by having the repository answer "empty" for somebody else's row.
- `JournalIntegrationTest` — the same rules over real HTTP against a real
  Postgres, plus what a unit test cannot show: that another user's entry is a **404,
  not a 403**, on both PATCH and DELETE and that the row is left untouched; that a
  non-existent id and somebody else's id give the identical status *and* code; that a
  `tradeId` belonging to another user is a 404, on create and on edit; that a link
  added on edit is stored and a second one is a 409 `ALREADY_LINKED`; that the list is this user's only and
  newest first; that `updated_at` really does arrive null and really does change; and
  that all four endpoints are 401 when logged out. **It uses no WireMock**, and points
  both provider base URLs at a dead port on purpose — if a journal request ever starts
  reaching for a price, these tests fail with a connection error instead of quietly
  passing. It also swaps in `JdkClientHttpRequestFactory`, because `TestRestTemplate`'s
  default factory is built on `HttpURLConnection` and rejects PATCH outright — a
  failure that looks like a controller bug and is not.
- `ReconnectBackoffTest` — the 1/2/4/8/16/30s schedule, the cap holding
  for ever, and no attempt count producing a zero delay. Pure arithmetic, so "does
  it behave after an hour of outage" is an assertion rather than an hour of
  waiting.

Run locally (Docker required):

```
cd backend
mvn test
```

Search reads only the DB and there is no ingestion path for *instruments*, so
the table is populated by `db/seed.sql` — 6 instruments (2 forex, 2 crypto, 2
stocks) and 90 `1day` candles each. That is what lets the app run with no API
key.

**Seed gap:** the seed holds `1day` candles only. `1day` serves the 6m range
and wants ~180, and the 1w / 1m / 1yr ranges (`2h`, `4h`, `1week`) have no seed
rows at all — so three of the four ranges always miss cache and call Twelve Data
live, against a free tier of roughly 8 requests/minute.

⚠️ Postgres runs `db/schema.sql` only when the data volume is empty.
