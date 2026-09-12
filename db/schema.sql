-- UC01: Search instrument — preliminary skeleton only, full model in MS4

CREATE TABLE instrument (
    symbol         VARCHAR(20) PRIMARY KEY,   -- e.g. 'EUR/USD', 'BTC/USD', 'AAPL'
    name           VARCHAR(100) NOT NULL,     -- display name, e.g. 'Apple Inc.'
    exchange       VARCHAR(50),               -- e.g. 'NASDAQ' for stocks; NULL for forex/crypto pairs
    type           VARCHAR(30) NOT NULL CHECK (type IN ('forex', 'crypto', 'stock')),  -- BR1: the app's three supported asset classes
    finnhub_symbol VARCHAR(20)                -- Finnhub's symbol for this instrument; differs from `symbol`
                                              -- for forex/crypto ('OANDA:EUR_USD', 'BINANCE:BTCUSDT'),
                                              -- usually identical for stocks (SCRUM-23 / SCRUM-25)
);
-- NOTE: renamed from `finnhubSymbol`. Postgres folds unquoted identifiers to
-- lowercase, so `finnhubSymbol` silently becomes `finnhubsymbol` in the actual
-- table -- every later reference would need "finnhubSymbol" in double quotes to
-- work. snake_case avoids that entirely, and matches the DB requirements doc.

CREATE TABLE price_candle (
    symbol      VARCHAR(20) NOT NULL REFERENCES instrument(symbol),
    interval    VARCHAR(10) NOT NULL CHECK (interval IN ('2h', '4h', '1day', '1week')),
    datetime    TIMESTAMP NOT NULL,
    open        NUMERIC(18,5) NOT NULL,
    high        NUMERIC(18,5) NOT NULL,
    low         NUMERIC(18,5) NOT NULL,
    close       NUMERIC(18,5) NOT NULL,
    volume      BIGINT,
    PRIMARY KEY (symbol, interval, datetime)
);
-- Interval strings are fixed to the four the app actually uses, and are spelled
-- the way Twelve Data spells them in its own `interval` request parameter, so the
-- mapping from API response -> stored rows needs no translation.
--
-- The CHECK is deliberate: without it, a single typo ('1d' instead of '1day')
-- inserts happily and then every read for that symbol silently returns zero rows
-- -- which looks exactly like "not ingested yet" and is painful to debug. Drop it
-- if you'd rather keep the column open-ended, but then the four functions below
-- and the ingestion code have to agree on spelling by convention alone.
--
-- Chart range -> interval (SCRUM-61): 1w -> 2h, 1m -> 4h, 6m -> 1day, 1yr -> 1week.


-- ============================================================
-- Read functions
--
-- REFERENCE ONLY (2026-08-31): the application does NOT call these. Instrument
-- search, the per-range candle window and the staleness rule are all
-- implemented in Java (InstrumentRepository, PriceRepository, PriceService).
-- These are kept as the DB-side statement of the same rules, and as the smoke
-- tests at the bottom of this file. Two independent implementations of one rule
-- is how SCRUM-52 happened -- if a rule changes, the Java is authoritative.
--
-- Named snake_case rather than camelCase (getInstruments etc. in the
-- requirements doc) for the same reason as finnhub_symbol above: Postgres
-- lowercases unquoted identifiers, so getInstruments would have to be called
-- as "getInstruments" with quotes forever. Same functions, safer spelling.
-- ============================================================

-- Instrument search (UC01 steps 4-8). Matches the user's input against both
-- symbol and name, case-insensitively, as a substring -- so "eur" finds
-- 'EUR/USD', and "apple" finds 'AAPL'.
--
-- Results are ranked: exact symbol match first, then symbol prefix, then name
-- prefix, then any other substring hit; ties broken alphabetically by name.
--
-- "Not found" signal: an EMPTY RESULT SET, not NULL. This replaces the old
-- search_instrument(), which returned a single symbol or NULL -- that only ever
-- worked because it returned one row. The backend maps an empty set to the REST
-- API's 404 / NOT_FOUND response (see backend/CONTRACTS.md), so the DB signal and
-- the API signal stay in lock-step.
CREATE OR REPLACE FUNCTION get_instruments(p_query VARCHAR)
RETURNS TABLE (
    symbol   VARCHAR(20),
    name     VARCHAR(100),
    type     VARCHAR(30),
    exchange VARCHAR(50)
) AS $$
BEGIN
    RETURN QUERY
    SELECT i.symbol, i.name, i.type, i.exchange
    FROM instrument i
    WHERE i.symbol ILIKE '%' || p_query || '%'
       OR i.name   ILIKE '%' || p_query || '%'
    ORDER BY
        CASE
            WHEN i.symbol ILIKE p_query           THEN 0   -- exact symbol
            WHEN i.symbol ILIKE p_query || '%'    THEN 1   -- symbol starts with
            WHEN i.name   ILIKE p_query || '%'    THEN 2   -- name starts with
            ELSE 3                                          -- substring anywhere
        END,
        i.name;
END;
$$ LANGUAGE plpgsql;

-- Daily candles for the chart (SCRUM-61: the 6m range uses 1day).
--
-- p_limit is "the most recent N candles", NOT the first N ever stored -- a 3m
-- chart wants the last ~90 days, not the 90 oldest rows we happen to have. The
-- inner query takes the newest N (datetime DESC), the outer one flips them back
-- into chronological order, which is what a chart needs to plot left-to-right.
--
-- Returns an empty set if the symbol is unknown or has no daily candles yet;
-- call check_price_data() first if you need to tell those apart from "we have
-- data but it's stale".
CREATE OR REPLACE FUNCTION get_daily_price(p_symbol VARCHAR, p_limit INTEGER)
RETURNS TABLE (
    datetime TIMESTAMP,
    open     NUMERIC(18,5),
    high     NUMERIC(18,5),
    low      NUMERIC(18,5),
    close    NUMERIC(18,5),
    volume   BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT latest.datetime, latest.open, latest.high, latest.low, latest.close, latest.volume
    FROM (
        SELECT c.datetime, c.open, c.high, c.low, c.close, c.volume
        FROM price_candle c
        WHERE c.symbol = p_symbol
          AND c.interval = '1day'
        ORDER BY c.datetime DESC
        LIMIT p_limit
    ) latest
    ORDER BY latest.datetime;
END;
$$ LANGUAGE plpgsql;
-- get_four_hr_price and get_weekly_price are the same body with '4h' / '1week'
-- substituted for '1day' -- left for Glenn per the requirements doc.

-- Reports whether we have usable price data for a symbol at a given interval, so
-- the business logic layer knows whether it needs to trigger ingestion from
-- Twelve Data before showing a chart (UC01 step 10 / extension 10a: "fetches (or
-- reuses cached) current price/chart data... falls back to cached data with an
-- outdated notice, or an error state if nothing is cached").
--
-- Returns one of:
--   'MISSING'      -> zero rows for this symbol+interval: nothing to show,
--                      a full fetch is needed.
--   'INSUFFICIENT' -> some rows exist, but either too few to plot a meaningful
--                      chart (< min_candles) or the newest candle is older than
--                      that interval's own staleness threshold (we're missing at
--                      least the most recent candle).
--   'OK'           -> enough recent data, serve straight from cache.
--
-- MISSING and INSUFFICIENT both mean "call ingestion" as far as the caller is
-- concerned -- they're only split out so ingestion/logging can tell "nothing at
-- all" apart from "have something, but it's stale or too thin".
--
-- Now takes p_interval, since price_candle holds all four intervals: asking
-- "do we have data for EUR/USD" is meaningless without saying at which interval.
-- An unknown interval raises rather than returning a value, so a typo surfaces
-- immediately instead of masquerading as MISSING.
--
-- NOTE 1: TIMEZONE CONVENTION -- intraday (2h, 4h) `datetime` values are UTC;
-- 1day and 1week values are the exchange's trading date, with no meaningful time
-- of day. This is guaranteed at ingestion: the Twelve Data request sends
-- timezone=UTC, which that API applies to intraday intervals and ignores for
-- daily/weekly. It is NOT an assumption -- it was one until 2026-09-08, and it
-- was wrong: the parameter defaults to "Exchange", so candles were arriving on
-- each instrument's own exchange clock. NOW() here is timestamptz and is
-- compared via the session's timezone setting; the Java side does the same
-- comparison explicitly in UTC (PriceService.needsIngestion).
--
-- NOTE 2: the staleness threshold is exactly one interval, which is strict.
-- Forex and stock markets close on weekends, so on a Sunday the newest 4h candle
-- is legitimately hours old and this reports INSUFFICIENT, triggering an
-- ingestion call that finds nothing new. If that turns into wasted Twelve Data
-- requests against the 800/day cap, widen the thresholds (e.g. 1.5x the interval)
-- or make them market-hours aware.
DROP FUNCTION IF EXISTS check_price_data(VARCHAR);  -- the old 1-arg version; CREATE OR REPLACE
                                                    -- below would otherwise ADD an overload
                                                    -- rather than replace it, leaving both live
CREATE OR REPLACE FUNCTION check_price_data(p_symbol VARCHAR, p_interval VARCHAR)
RETURNS VARCHAR AS $$
DECLARE
    row_count            INTEGER;
    latest_dt            TIMESTAMP;
    staleness_threshold  INTERVAL;
    min_candles          CONSTANT INTEGER := 2;
BEGIN
    staleness_threshold := CASE p_interval
        WHEN '4h'    THEN INTERVAL '4 hours'
        WHEN '1day'  THEN INTERVAL '1 day'
        WHEN '1week' THEN INTERVAL '7 days'
    END;

    IF staleness_threshold IS NULL THEN
        RAISE EXCEPTION 'Unknown interval: %. Expected one of 4h, 1day, 1week.', p_interval;
    END IF;

    SELECT COUNT(*), MAX(c.datetime) INTO row_count, latest_dt
    FROM price_candle c
    WHERE c.symbol = p_symbol
      AND c.interval = p_interval;

    IF row_count = 0 THEN
        RETURN 'MISSING';
    ELSIF row_count < min_candles OR latest_dt < (NOW() - staleness_threshold) THEN
        RETURN 'INSUFFICIENT';
    ELSE
        RETURN 'OK';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- Manual smoke test (run by hand in psql once the container is up)
-- ============================================================
-- INSERT INTO instrument (symbol, name, exchange, type, finnhub_symbol)
--   VALUES ('EUR/USD', 'Euro / US Dollar', NULL, 'forex', 'OANDA:EUR_USD');
-- INSERT INTO instrument (symbol, name, exchange, type, finnhub_symbol)
--   VALUES ('AAPL', 'Apple Inc.', 'NASDAQ', 'stock', 'AAPL');
-- INSERT INTO instrument (symbol, name, exchange, type, finnhub_symbol)
--   VALUES ('BTC/USD', 'Bitcoin / US Dollar', NULL, 'crypto', 'BINANCE:BTCUSDT');
-- INSERT INTO price_candle (symbol, interval, datetime, open, high, low, close, volume)
--   VALUES ('EUR/USD', '1day', '2026-08-22 00:00:00', 1.0800, 1.0820, 1.0790, 1.0810, NULL);
--
-- SELECT * FROM get_instruments('EUR/USD');       -- exact symbol match -> one row, EUR/USD
-- SELECT * FROM get_instruments('apple');         -- case-insensitive name match -> AAPL
-- SELECT * FROM get_instruments('doesnotexist');  -- expect: 0 rows (the "not found" signal)
--
-- SELECT * FROM get_daily_price('EUR/USD', 30);   -- expect: the 1 candle above
-- SELECT * FROM get_daily_price('AAPL', 30);      -- expect: 0 rows
--
-- SELECT check_price_data('BTC/USD', '1day');     -- expect: 'MISSING' (zero rows)
-- SELECT check_price_data('EUR/USD', '1day');     -- expect: 'INSUFFICIENT' (1 old candle)
-- SELECT check_price_data('EUR/USD', '4h');       -- expect: 'MISSING' (no 4h rows)
-- SELECT check_price_data('EUR/USD', 'banana');   -- expect: ERROR, unknown interval
