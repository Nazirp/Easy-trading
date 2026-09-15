# Interface Contracts (SCRUM-36)

**These endpoint paths and response shapes are final.** The frontend builds
against them now; later milestones only *add* endpoints and *fill in* fields
that already exist — nothing here gets renamed.

Scope note (2026-08-23): `/api/search` reads the local DB only. It does not
call Twelve Data. `/api/getPrice` does call Twelve Data, but only when it has
no cached candles for that symbol+interval, or the ones it has are stale (see
§5).

## 0. Deployment shape — one origin

This application serves both the JSON API and the frontend's HTML/JS/CSS. The
frontend's files live in `backend/src/main/resources/static/`; `WebConfig` maps the one
clean URL the frontend needs (`/demo-trading` → `demo-trading.html`).

Everything under `/api/**` is JSON; everything else is a page or an asset. That
split is what lets a page route be added without ever shadowing an endpoint.

The reason for one origin rather than a separate frontend server is MS4's
authentication: session cookies are origin-scoped, so a split origin would need
CORS on every endpoint, `credentials: 'include'` on every fetch, and
`SameSite=None; Secure` cookies — which requires HTTPS and is painful locally.
Same origin makes all of that unnecessary. It also keeps the MS4 Docker Compose
deliverable (O5) at two services instead of three. Layer separation is
unaffected: the UI layer is still its own code calling the REST layer over HTTP.

## 1. REST API — frontend ↔ backend

### `GET /api/search?q={query}` (real, tested)

**200 OK** — always non-empty; no match is the 404 below.

```json
{
  "results": [
    { "symbol": "EUR/USD", "name": "Euro / US Dollar", "type": "forex" }
  ]
}
```

`type` is one of `"forex" | "crypto" | "stock"` — lowercase, matching BR1 and
`instrument.type` in the DB.

**404 Not Found** — no instrument matches:

```json
{ "code": "NOT_FOUND", "message": "No instrument found for 'xyz'." }
```

This is UC01 extension 7a, not a failure: the frontend should render the
friendly empty state, not a raw error.

**400 Bad Request** — empty/missing query (UC01 extension 2a):

```json
{ "code": "INVALID_QUERY", "message": "Please enter an instrument symbol or name." }
```

### `GET /api/getPrice?symbol={symbol}&interval={interval}` (real, tested)

`interval` is one of `2h | 4h | 1day | 1week`, defaulting to `1day` if omitted.
Chart range → interval (SCRUM-61): `1w→2h`, `1m→4h`, `6m→1day`, `1yr→1week`.
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

> **Default note:** the default is still `1day`, which under this mapping is the
> *6m* range — not the frontend's default view. Open decision (see SCRUM-61).

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
  its signal (UC02 BR1). `verdict` is `BUY | SELL | HOLD | NONE`; `NONE` is the
  neutral "not enough data yet" state (UC02 5a) — still a 200, still render the
  chart. Computed for real since SCRUM-64 (SMA 10 vs SMA 20 crossover within a
  3-candle look-back); `NONE` below 21 candles.

**404 Not Found** — unknown symbol, same signal as `/api/search`:

```json
{ "code": "NOT_FOUND", "message": "No instrument found for 'XYZ'." }
```

**400 Bad Request** — interval outside the supported four:

```json
{ "code": "INVALID_INTERVAL", "message": "Unknown interval 'banana'. Expected one of: 2h, 4h, 1day, 1week." }
```

### Authentication (SCRUM-39 / SCRUM-66) — real, tested

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

A new account starts at the default virtual balance of $10,000 (UC04 BR1).

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

`/api/search` and `/api/getPrice` stay fully public, per the UC01/UC02
preconditions — an account is required only for user-scoped features. There is
no security filter chain in front of the application; endpoints that need a user
ask for one explicitly (`SessionUser.require`), which is why adding an endpoint
can never accidentally lock the public ones.

#### `INVALID_BODY`

**400** on any POST whose JSON body is missing or unparseable, in the API's own
error shape rather than Spring's default one.

### Watchlist (SCRUM-22 / SCRUM-70) — real, tested

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
only (UC03 AC).

`"items": []` is a normal 200: the account exists and has saved nothing.

#### `POST /api/watchlist`

Body `{ "symbol": "BTC/USD" }`.

**201 Created** — returns the saved instrument in the same item shape as above,
so the frontend can render the new row from the response instead of re-fetching
the list.

**404 `NOT_FOUND`** — no such instrument. A missing or blank `symbol` matches
nothing and gives the same 404.
**409 `ALREADY_ON_WATCHLIST`** — the user has already saved it (UC03 BR1). Show
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

### Demo trading — price feed (SCRUM-72) — real, tested

The two endpoints the demo-trading chart is drawn from. **This is the price feed
only**: no trades, no positions, no balance, no P&L — those are the rest of
SCRUM-45 and will be added here when they are built.

Demo trading is **BTC/USD only** (UC04 BR6). Both endpoints take a `symbol`,
both default it to `BTC/USD`, and **anything else is a 404 `NOT_FOUND`** —
deliberately not a silent redirect to BTC/USD, so a frontend bug is visible
rather than showing the wrong instrument's chart. Both require a login.

#### `GET /api/getLiveHistory?symbol={symbol}`

The chart's starting state — roughly the last 30 minutes of the instrument, so
the page is never a blank rectangle. Called **once**, when the page opens.

**200 OK**

```json
{
  "symbol": "BTC/USD",
  "points": [
    { "datetime": "2026-09-15T12:00:00", "price": 63080.00 },
    { "datetime": "2026-09-15T12:01:00", "price": 63100.00 }
  ]
}
```

`points` is **oldest first** — a chart is drawn left to right; Twelve Data
answers newest-first and the backend reverses it, so the frontend never has to.
Each point is one 1-minute candle reduced to its **close**: the live tail is a
line of single prices (BR3), so the past is drawn the same way.

`datetime` follows §2's convention for intraday values — **it is UTC and carries
no zone**, so the frontend must append `Z` before parsing, exactly as it already
does for `2h`/`4h` candles from `/api/getPrice`.

**An empty `points` array is a valid 200, not an error** (UC04 extension 5a). It
means the history is unavailable — Twelve Data down, or the day's 800-request
budget spent. The page should open with an empty chart and a short note and then
fill from the live feed: a missing past is a degraded chart, never a blocked
page.

#### `GET /api/getLivePrice?symbol={symbol}`

One current price. Polled every 5 seconds for as long as the page is open **and
visible**.

**200 OK**

```json
{ "symbol": "BTC/USD", "price": 63140.00, "timestamp": "2026-09-15T12:06:40Z", "outdated": false }
```

**One price, not a candle** (UC04 BR3) — there is no open/high/low/close here,
because one value cannot carry them. `timestamp` is an instant (with a zone),
unlike the backfill's `datetime`; the frontend should append a point only when
the timestamp has moved.

`outdated: true` means Finnhub could not be reached on this cycle and **the last
good price is being re-served**. The status is still 200 — the request was fine
and there *is* a price, it is just not brand new — so the page keeps drawing and
shows a small "may be outdated" note (UC04 6a/6b). The frontend cannot work this
out for itself: an unchanged price is normal when nothing traded.

**503 Service Unavailable** — Finnhub failed *and* nothing is cached yet:

```json
{ "code": "LIVE_PRICE_UNAVAILABLE", "message": "The live price feed is unavailable right now. Please try again in a moment." }
```

503 rather than 500 on purpose: nothing in this application is broken, an
upstream provider is unavailable or rate-limited, and the next poll four seconds
later may well succeed.

#### The 4-second cache

`LivePriceService` holds **the last `LivePrice` and the `Instant` it was fetched,
in a field**, and serves it for 4 seconds before calling Finnhub again.

Without it, one open page costs ~12 Finnhub requests a minute against a free tier
of ~30–60, and three people demoing at once would be rate-limited mid-demo. With
it the upstream cost depends on *time* rather than on how many people are
watching: N pages still cost ~12 calls a minute between them, and nobody ever
sees a price more than four seconds old. Four rather than five so a 5-second poll
never *just* misses the window.

**It is a field, not a table.** The value is worthless four seconds after it is
written, so persisting it would mean a row per upstream call that is never read
again. `price_candle` caches history because history is still true tomorrow; this
is not. A restart simply refetches. The TTL is `liveprice.cache-ttl` in
`application.yml` — a property only so tests can shrink it to zero and drive the
expiry path without sleeping. **One instrument means one slot; a second
instrument turns the field into a `Map<String, CachedQuote>`** and changes
nothing else.

#### The backfill is never persisted

`LiveChartService` calls `MarketDataClient.getCandles(symbol, "1min", 30)`
**directly**, bypassing `PriceService` and the `Interval` enum, and injects no
repository at all. `1min` is not one of our four chart intervals, `price_candle`'s
`CHECK` constraint does not allow it, and 1-minute candles from an hour ago are
not "recent" in any sense the live chart means. These points are scenery for the
first half hour of a demo and are gone from the 30-minute window after that.

#### The Finnhub symbol is a constant

Finnhub spells BTC/USD `BINANCE:BTCUSDT`. That mapping lives in
`DemoInstrument.FINNHUB_SYMBOL`, next to the client. It used to be the column
`instrument.finnhub_symbol`, **removed on 2026-09-15**: one useful value spread
across six rows is not a model of anything, and nothing outside this feature ever
read it. A database built before that date fails `ddl-auto: validate` on
startup — `docker compose down -v && docker compose up --build`.

### Still to come (MS4)

`/api/account/cash`, `/api/account/positions`, `/api/trades`, `/api/journal`.
These are additions, not changes to the above.

Their shapes used to be sketched in `Isna_Instructions.txt`, which was deleted on
2026-09-15: it had drifted (it still described a demo-trading page with its own
instrument search) and it was a second copy of this file's job. **This file is
now the only contract the frontend builds against.** Each shape is written here
when the endpoint is built, not before.

Each of them is user-scoped and must reject an anonymous caller with
**401 `NOT_AUTHENTICATED`** — the same code `/api/me` uses — rather than a 500,
an empty list, or somebody else's data. The way to do that is to take an
`HttpSession` and call `SessionUser.require(session)`; see `AuthController.me`.

## 2. `MarketDataClient` — backend ↔ Twelve Data

```java
public interface MarketDataClient {
    List<InstrumentMatch> searchInstruments(String query); // paper contract — search is DB-only, never called
    Quote getQuote(String symbol);                          // paper contract — nothing needs a single live quote yet
    List<Candle> getCandles(String symbol, String interval, int outputSize); // REAL, wired — called by PriceService on a cache miss
}

public record InstrumentMatch(String symbol, String name, String exchange, InstrumentType type);
public record Quote(String symbol, BigDecimal price, Instant timestamp);
public record Candle(LocalDateTime datetime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume);
```

`TwelveDataMarketDataClient.getCandles` calls
`GET /time_series?symbol={symbol}&interval={interval}&outputsize={n}&apikey={key}`
and maps the raw string-typed fields into `Candle`.

`outputsize` is always sent: omitting it makes Twelve Data return its default of
30 candles, short of every chart range (SCRUM-62). `n` is the interval's display
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

`searchInstruments` and `getQuote` throw `UnsupportedOperationException`:
defined for the contract, not implemented, since nothing calls them in MS3.

## 3. `LivePriceClient` — backend ↔ Finnhub (real, wired — SCRUM-72)

```java
public interface LivePriceClient {
    LivePrice getLivePrice(String finnhubSymbol);  // REAL — FinnhubLivePriceClient, called by LivePriceService
}

public record LivePrice(String symbol, BigDecimal price, Instant timestamp);
```

Was a paper contract through MS3; **implemented in SCRUM-72, with the shape
unchanged** — exactly as this file promised. `FinnhubLivePriceClient` calls
`GET /quote?symbol={finnhubSymbol}&token={key}` and maps `c` (current price) and
`t` (UNIX timestamp in **seconds**) into `LivePrice`.

The REST quote endpoint, deliberately **not** the trade-tick WebSocket: a socket
only speaks when a trade happens, so a thin pair can go silent for minutes, and a
graded demo cannot depend on the market being busy at that moment. Each poll
returns **one current price point, not a candle**; the live chart is built by
appending points.

Two details worth knowing before touching this:

- **The parameter is Finnhub's spelling, not ours.** The client does not
  translate; `LivePriceService` passes `DemoInstrument.FINNHUB_SYMBOL`. What comes
  back carries *our* symbol (`BTC/USD`), so nothing downstream has to know the
  provider's naming.
- **A price of `0` is a failure, not a price.** Finnhub answers `200` with
  `{"c":0}` for a symbol it has no data on — there is no 404 — so the client
  treats that as a failed call rather than plotting $0.

The client is a mapper and nothing else: caching, fallback and the login gate all
live above it, in `LivePriceService` and `LivePriceController`. Same division
`TwelveDataMarketDataClient` has with `PriceService`.

Two providers now means **two `RestClient` beans** in the context
(`twelveDataRestClient`, `finnhubRestClient`), so both clients inject theirs by
`@Qualifier` rather than by type. Finnhub's bean also carries connect/read
timeouts, because `LivePriceService` holds a lock across that call.

## 4. Persistence

Schema is `db/schema.sql`, which is the contract on the DB side. Entities map
1:1: `Instrument` → `instrument`, `Price` → `price_candle` (composite PK
`symbol, interval, datetime`), `User` → `app_user` (surrogate `id`, `username`
`UNIQUE`, `password_hash`, `cash_balance`), `WatchlistEntry` → `watchlist`
(composite PK `user_id, symbol`). Hibernate runs with
`ddl-auto: validate` — it never creates or alters tables, only checks the
mapping against the applied schema.

**Not everything the API returns is stored, and one thing deliberately never is.**
The demo-trading backfill (`/api/getLiveHistory`) and the live quote
(`/api/getLivePrice`) have no entity, no table and no repository between them —
see §1. `price_candle` holds the four chart intervals and nothing else; its
`CHECK` constraint enforces that.

**Passwords are hashed in Java and nowhere else.** `app_user` has a
`password_hash` column and no `password` column; BCrypt (`spring-security-crypto`,
not the full `spring-boot-starter-security`) hashes on registration and compares
on login, inside `AuthService`. No SQL statement in this application ever
receives a plaintext password, which is also why `db/schema.sql` deliberately
contains no login or verify function.

`User.STARTING_CASH` and the `DEFAULT 10000.00` on the column state the same
rule twice; as everywhere else here, **the Java is authoritative** and the DB
default is a backstop for rows inserted by hand. `created_at` is the exception,
owned by the database (`DEFAULT NOW() AT TIME ZONE 'UTC'`) so every row lands on
one clock whatever the server's timezone.

**The SQL functions in `db/schema.sql` are reference only — the application
never calls them.** `get_instruments()`, `get_daily_price()` and
`check_price_data()` are mirrored in Java (`InstrumentRepository`,
`PriceRepository` + `PriceService`), and all window and staleness logic lives
there. `get_two_hr_price` / `get_four_hr_price` / `get_weekly_price` were never
written and are not needed. Two independent implementations of the same rule is
how SCRUM-52 happened, so treat the Java as authoritative and the SQL as
documentation.

## 5. Not yet implemented, deliberately

Nothing on the `/api/search` + `/api/getPrice` + auth + watchlist + demo-trading
price feed surface. The endpoints in "Still to come" above are the remaining MS4
work: the demo-trading page can now draw its chart, but it cannot yet place a
trade, hold a position or show P&L.

**Done since this section was first written — signal computation** (SCRUM-46 /
SCRUM-64). The `signal` field carried a hard-coded `NONE` when this section was
written; it is now computed. The shape never changed, exactly as promised.

**Done since this section was first written — staleness-aware refresh.**
`PriceService.needsIngestion()` classifies the cache MISSING / INSUFFICIENT / OK
in Java and re-ingests when the newest candle is older than **1.5x the candle
length** (`Interval.stalenessThreshold()`). 1.5x rather than exactly one candle
because a closed market otherwise reads as permanently stale: on a Sunday the
newest `2h` candle for a forex pair is legitimately hours old, and every
1w-range page load would fire an ingest that returns nothing new, against Twelve
Data's 800/day cap. It does **not** call `check_price_data()` — see §4.

**Done since this section was first written — the Finnhub integration.** §3 said
"paper contract, MS4" and "wired up once UC04 is built". It is built: SCRUM-72
implemented `LivePriceClient` as `FinnhubLivePriceClient` and put it behind
`/api/getLivePrice`. The interface is byte-for-byte the shape agreed in MS3.
Twelve Data's own `getQuote` is still a paper contract and is still not called by
anything — the live quote comes from Finnhub by design (UC04 BR2).

## 6. Where this was verified

Testcontainers-Postgres integration tests, plus plain unit tests where a
container would add nothing:

- `InstrumentSearchIntegrationTest` — a match is returned; no match gives
  `NOT_FOUND`; an empty query is rejected before the DB is touched.
- `PriceIntegrationTest` — cached candles are served with no WireMock call;
  unknown symbol gives `NOT_FOUND`; unknown interval gives `INVALID_INTERVAL`;
  and an instrument with no candles at the requested interval triggers a real
  call to a WireMock-stubbed Twelve Data, with the result both returned and
  persisted under the right interval. SCRUM-62 added `2h` and `1week` ingest
  cases, an assertion that `outputsize` actually goes out on the request, and a
  case proving the returned series is capped at the interval's window and comes
  back oldest-first.
- `PriceServiceTest` — the MISSING / INSUFFICIENT / OK classification as a plain
  unit test, no Spring context and no database.
- `WatchlistIntegrationTest` (SCRUM-70) — a new account's list is empty;
  adding puts the instrument on it and returns it; the list is oldest-first, not
  alphabetical; adding twice gives 409; an unknown symbol gives 404; removing is
  idempotent; a symbol containing a slash survives the DELETE round trip; two
  users can save the same instrument and neither sees the other's list; all
  three endpoints give 401 when logged out, and again after logout.
- `AuthIntegrationTest` (SCRUM-66) — signup creates an account at $10,000 and
  stores a 60-character BCrypt hash rather than the password; a taken username
  gives 409; a short password gives 400 and writes no row; login then `/api/me`
  round-trips the session cookie; a wrong password and an unknown username fail
  *identically*; `/api/me` is 401 when logged out and again after logout; the
  session id changes on login (fixation); and `/api/search` and `/api/getPrice`
  still answer 404, not 401, with no account at all.
- `LiveTradingIntegrationTest` (SCRUM-72) — the backfill returns 1-minute
  closes **oldest first** and asks Twelve Data for `interval=1min` with an
  explicit `outputsize`; it writes **nothing** to `price_candle`; a dead Twelve
  Data gives an empty `points` array and a 200, not an error; a symbol other than
  `BTC/USD` gives 404; two polls inside the cache window cost **one** Finnhub
  call and the outgoing symbol is `BINANCE:BTCUSDT`; both endpoints give 401 when
  logged out. Two WireMock servers, not one, so asking the wrong provider for the
  wrong thing cannot pass unnoticed.
- `LivePriceFallbackIntegrationTest` (SCRUM-72) — UC04 6a/6b over HTTP, with
  `liveprice.cache-ttl` set to zero so every request takes the expiry branch: a
  cold cache plus a dead Finnhub is 503 `LIVE_PRICE_UNAVAILABLE`; a warm one
  answers 200 with the last price and `outdated: true`; Finnhub's `{"c":0}` is
  treated as a failure rather than a price of zero. No sleeps, so nothing here
  can flake on a loaded machine.
- `LivePriceServiceTest` (SCRUM-72) — the cache and its fallback as a plain unit
  test: a hit inside the window, a refetch after expiry, the stale-but-served
  path, the cold-cache error, recovery on the next good poll, and a wrong symbol
  rejected before the provider is touched.

**Not run in the sandbox this was written in** — that environment blocks Maven
Central, so `mvn test` couldn't execute. Run locally (Docker required):

```
cd backend
mvn test
```

Search reads only the DB and there is no ingestion path for *instruments*, so
the table is populated by `db/seed.sql` — 6 instruments (2 forex, 2 crypto, 2
stocks) and 90 `1day` candles each. That is what lets the app run with no API
key.

**Seed gap:** the seed holds `1day` candles only. `1day` now serves the 6m range
and wants ~180, and the 1w / 1m / 1yr ranges (`2h`, `4h`, `1week`) have no seed
rows at all — so three of the four ranges always miss cache and call Twelve Data
live, against a free tier of roughly 8 requests/minute.

⚠️ Postgres runs `db/schema.sql` only when the data volume is empty. A volume
created before `2h` was added to the `interval` CHECK will reject every `2h`
insert: `docker compose down -v && docker compose up --build`.
