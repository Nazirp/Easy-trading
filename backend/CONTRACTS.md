# Interface Contracts (SCRUM-36)

**These endpoint paths and response shapes are final.** The frontend builds
against them now; later milestones only *add* endpoints and *fill in* fields
that already exist — nothing here gets renamed.

Scope note (2026-08-23): `/api/search` reads the local DB only. It does not
call Twelve Data. `/api/getPrice` does call Twelve Data, but only when it has
no cached candles for that symbol+interval.

## 0. Deployment shape — one origin

This application serves both the JSON API and the frontend's HTML/JS/CSS. The
frontend's files live in `src/main/resources/static/`; `WebConfig` maps the one
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
Chart range → interval (SCRUM-20): `1w→2h`, `1m→4h`, `6m→1day`, `1yr→1week`.
Each range maps to its own interval, so `interval` alone identifies the range —
the number of candles to return is derived from the interval in the business
logic layer, not sent by the frontend.

> **Default note:** the default is still `1day`, which under this mapping is the
> *6m* range — not the frontend's default view. Open decision (see SCRUM-20).

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
  chart. **MS3 always returns `NONE`**: signal computation is SCRUM-20 work for
  MS4. The field exists now so only its values change later, not the shape.

**404 Not Found** — unknown symbol, same signal as `/api/search`:

```json
{ "code": "NOT_FOUND", "message": "No instrument found for 'XYZ'." }
```

**400 Bad Request** — interval outside the supported four:

```json
{ "code": "INVALID_INTERVAL", "message": "Unknown interval 'banana'. Expected one of: 2h, 4h, 1day, 1week." }
```

### Still to come (MS4)

`/api/login`, `/api/signup`, `/api/watchlist`, `/api/account/cash`,
`/api/account/positions`, `/api/getLivePrice`, `/api/trades`, `/api/journal`.
Shapes sketched in the frontend instructions; these are additions, not changes
to the above.

## 2. `MarketDataClient` — backend ↔ Twelve Data

```java
public interface MarketDataClient {
    List<InstrumentMatch> searchInstruments(String query); // paper contract — search is DB-only, never called
    Quote getQuote(String symbol);                          // paper contract — nothing needs a single live quote yet
    List<Candle> getCandles(String symbol, String interval); // REAL, wired — called by PriceService on a cache miss
}

public record InstrumentMatch(String symbol, String name, String exchange, InstrumentType type);
public record Quote(String symbol, BigDecimal price, Instant timestamp);
public record Candle(LocalDateTime datetime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume);
```

`TwelveDataMarketDataClient.getCandles` calls
`GET /time_series?symbol={symbol}&interval={interval}&apikey={key}` and maps the
raw string-typed fields into `Candle`. Twelve Data formats datetimes per
interval — `"2026-08-22"` for daily/weekly, `"2026-08-22 12:00:00"` for 2h/4h —
both normalized to `LocalDateTime` at the client boundary.

`searchInstruments` and `getQuote` throw `UnsupportedOperationException`:
defined for the contract, not implemented, since nothing calls them in MS3.

## 3. `LivePriceClient` — backend ↔ Finnhub (paper contract, MS4)

```java
public interface LivePriceClient {
    LivePrice getLivePrice(String symbol);
}

public record LivePrice(String symbol, BigDecimal price, Instant timestamp);
```

No implementation class, nothing calls this in MS3. Finnhub, polled every 5
seconds via the quote endpoint — each poll returns **one current price point,
not a candle**; the live chart is built by appending points. (Not the trade-tick
WebSocket, which can go quiet for a given pair — bad for a gradeable demo.)
Wired up once UC04 is built in MS4.

## 4. Persistence

Schema is `db/schema.sql`, which is the contract on the DB side. Entities map
1:1: `Instrument` → `instrument`, `Price` → `price_candle` (composite PK
`symbol, interval, datetime`). Hibernate runs with `ddl-auto: validate` — it
never creates or alters tables, only checks the mapping against the applied
schema.

## 5. Not yet implemented, deliberately

- **Staleness-aware refresh.** `PriceService` only checks "do we have any rows
  for this symbol+interval" before ingesting. It does not yet use the
  `MISSING` / `INSUFFICIENT` / `OK` distinction `check_price_data()` already
  models — so stale data is served indefinitely rather than re-fetched.
- **Signal computation** (SCRUM-20) — see the `signal` field note above.

## 6. Where this was verified

Two Testcontainers-Postgres integration tests:

- `InstrumentSearchIntegrationTest` — a match is returned; no match gives
  `NOT_FOUND`; an empty query is rejected before the DB is touched.
- `PriceIntegrationTest` — cached candles are served with no WireMock call;
  unknown symbol gives `NOT_FOUND`; unknown interval gives `INVALID_INTERVAL`;
  and an instrument with no candles at the requested interval triggers a real
  call to a WireMock-stubbed Twelve Data, with the result both returned and
  persisted under the right interval.

**Not run in the sandbox this was written in** — that environment blocks Maven
Central, so `mvn test` couldn't execute. Run locally (Docker required):

```
cd backend
mvn test
```

Search reads only the DB and there is no ingestion path for *instruments* yet,
so the table starts empty. To see a non-empty search result locally, insert a
row by hand first (see the smoke test at the bottom of `db/schema.sql`):

```sql
INSERT INTO instrument (symbol, name, exchange, type, finnhub_symbol)
  VALUES ('EUR/USD', 'Euro / US Dollar', NULL, 'forex', 'OANDA:EUR_USD');
```

`/api/getPrice` for that symbol will then ingest its candles from Twelve Data on
the first call.
