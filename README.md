# Easy Trading — MS3 skeleton

Beginner-friendly multi-asset trading app (Team 9 "Amigos", Informatik 3, HTW Berlin).

MS3 proves one use case end to end: **UC01 — Search Instrument**. A search runs
against the database, and selecting a result loads that instrument's price
history (from the DB, or ingested from Twelve Data on the first request) and
draws a chart.

```
frontend (static HTML/JS)  ->  REST layer  ->  business logic  ->  persistence (Postgres)
                                                             \->  Twelve Data client
```

## Layout

| Path | What it is | Owner |
|---|---|---|
| `db/schema.sql` | Tables + DB functions. The contract on the DB side. | Glenn |
| `db/seed.sql` | Demo instruments and candles, so it runs without an API key. | — |
| `backend/src/main/java/` | Spring Boot: REST, business logic, API clients, persistence. | Nazir |
| `backend/src/main/resources/static/` | Frontend HTML/CSS/JS, served by the backend. | Isna |
| `backend/CONTRACTS.md` | REST + client interface contracts. Also on Confluence. | Nazir |

## Running it locally

**Option A — Docker Compose (one command, no local Java/Postgres install needed).**

```bash
docker compose up --build
```

This builds the backend image and starts it alongside a seeded Postgres
container, wired together — nothing else to install. Open
<http://localhost:8080> once it's up. `Ctrl+C` to stop, `docker compose down`
to remove the containers (add `-v` to also drop the database volume and
reseed from scratch next time).

**Option B — run it directly on your machine.** Useful when you want to run
the backend from your IDE (breakpoints, hot reload via devtools) rather
than as a container.

**1. Start Postgres.** With Docker:

```bash
docker run --name easytrading-db -e POSTGRES_DB=easytrading \
  -e POSTGRES_USER=easytrading -e POSTGRES_PASSWORD=easytrading \
  -p 5433:5432 -d postgres:16
```

Or use a local install — the app expects database `easytrading`, user
`easytrading`, password `easytrading` (see `backend/src/main/resources/application.yml`;
the password can be overridden with the `DB_PASSWORD` environment variable). Port 5433, not Postgres's default 5432, because 5432 is often already taken by a locally-installed Postgres on student laptops.

**2. Apply the schema and the demo data.**

```bash
psql -h localhost -p 5433 -U easytrading -d easytrading -f db/schema.sql
psql -h localhost -p 5433 -U easytrading -d easytrading -f db/seed.sql
```

`seed.sql` gives you 6 instruments (2 forex, 2 crypto, 2 stocks) with 90 daily
candles each (1day only — no 2h/4h/1week rows yet). **This is what lets the app run without a Twelve Data API key** —
`/api/getPrice` only calls the provider when it finds no cached candles. Dates
are relative to `CURRENT_DATE`, so the data is always current whenever you seed.

**3. Run the backend.**

```bash
cd backend
mvn spring-boot:run
```

**4. Open <http://localhost:8080>** and search for `eur`, `bitcoin`, `apple`, …

To use real Twelve Data instead of the seeded candles, set `TWELVEDATA_API_KEY`
in your environment and query an instrument that has no candles stored yet.
(Works the same way with Option A: `TWELVEDATA_API_KEY=xxxx docker compose up --build`.)

## Running the tests

Requires Docker (Testcontainers starts a real Postgres):

```bash
cd backend
mvn test
```

## API

Two endpoints exist so far. Full detail — including error shapes — in
`backend/CONTRACTS.md`.

| Endpoint | Purpose |
|---|---|
| `GET /api/search?q={query}` | Instrument search. DB only, no external call. 404 = no match, 400 = empty query. |
| `GET /api/getPrice?symbol={symbol}&interval={interval}` | Candles + signal. `interval` is `2h`, `4h`, `1day` or `1week`. |

Everything under `/api/**` is JSON; everything else is a page or a static
asset. These paths are final — later milestones add endpoints, they don't
rename these.

## Notes for the team

- **Never commit API keys.** `application.yml` reads them from environment
  variables (`TWELVEDATA_API_KEY`, `FINNHUB_API_KEY`) — keep it that way. Git
  keeps history forever, so a committed key stays exposed even after deletion.
- **Hibernate runs with `ddl-auto: validate`** — it never creates or changes
  tables. `db/schema.sql` is the single source of truth; if you change a
  column, update the entity too or the app won't start.
- **Frontend files go in `backend/src/main/resources/static/`.** With the
  backend running, edit and refresh — `spring-boot-devtools` picks up changes
  without a rebuild.
