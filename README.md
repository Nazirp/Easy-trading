# Easy Trading

Beginner-friendly multi-asset trading app.

```
frontend (static HTML/JS)  ->  REST layer  ->  business logic  ->  persistence (Postgres)
                                                             \->  Twelve Data client (candles)
                                                             \->  Finnhub client (live trade stream)
```

## Layout

| Path | What it is |
|---|---|
| `db/schema.sql` | Tables. The contract on the DB side. |
| `db/seed.sql` | The instrument list. Candles are not seeded; they come from Twelve Data. |
| `backend/src/main/java/` | Spring Boot: REST, business logic, API clients, persistence. |
| `backend/src/main/resources/static/` | Frontend HTML/CSS/JS, served by the backend. |
| `backend/CONTRACTS.md` | REST + client interface contracts. Also on Confluence. |

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

**2. Apply the schema and the instrument list.**

```bash
psql -h localhost -p 5433 -U easytrading -d easytrading -f db/schema.sql
psql -h localhost -p 5433 -U easytrading -d easytrading -f db/seed.sql
```

`seed.sql` adds the 6 instruments (2 forex, 2 crypto, 2 stocks). It holds no
candles: each chart is fetched from Twelve Data the first time it is opened and
cached, so everything on screen is real market data.

**3. Run the backend.**

```bash
cd backend
mvn spring-boot:run
```

**4. Open <http://localhost:8080>** and search for `eur`, `bitcoin`, `apple`, …

**Charts need a Twelve Data key.** Set `TWELVEDATA_API_KEY` in your environment
(with Option A: `TWELVEDATA_API_KEY=xxxx docker compose up --build`). Until an
instrument's chart has been opened once, the home-page list shows it without a price.

**Demo trading needs both keys.** The chart on that page is backfilled from
Twelve Data (`TWELVEDATA_API_KEY`) and then built live from Finnhub's trade
stream (`FINNHUB_API_KEY`). Without the Twelve Data key the chart simply opens
empty and fills in from the present. Both keys are free to obtain.

## Running the tests

Requires Docker (Testcontainers starts a real Postgres):

```bash
cd backend
mvn test
```

## API

Full detail — including error shapes — in
`backend/CONTRACTS.md`.

Everything under `/api/**` is JSON; everything else is a page or a static
asset.

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
