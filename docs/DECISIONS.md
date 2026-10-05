# Design decisions and lessons

These are the decisions that shaped Easy Trading most, and the mistakes that
taught us the most. For each one: what happened, what we decided, and why.

## 1. Measure an external API before building on it

Demo trading was designed to poll Finnhub's REST quote every 5 seconds. Once
it was built, the price on screen only moved about every 15 seconds. The code
was fine; the endpoint simply refreshes that slowly. Nobody had measured it
before building the endpoint, the cache, the frontend and the tests on top of
it.

We then measured Finnhub's WebSocket before deciding anything. It delivered
about 20 trades a second, with a median delay of about 0.4 seconds, so we
switched to it. The REST quote stayed as a fallback while the stream
reconnects.

**Lesson:** our tests couldn't have caught this. A stub only returns what you
already believe. Measure the behaviour you rely on, not the behaviour the
documentation describes.

## 2. The server builds the candles, not the browser

A one-minute candle is built from roughly 1,200 trades, but a page polling once
a second sees only about 60 of them. If the browser built candles, highs and
lows would be systematically too narrow, and two viewers would see different
candles. The server sees every trade, so it builds the candles and the browser
draws what it receives. Candles also line up with the wall clock, so a page
reload loses nothing.

## 3. One response when two things must show the same moment

The live chart, the latest price and the account's profit and loss come back
in one response, `GET /api/getLiveChart`, not from separate endpoints. Fetched
separately, they would be read at different instants and could disagree on
screen. The journal goes the other way: an entry is fetched separately from
its trade, because a closed trade never changes.

**Rule:** split endpoints by what has to be true at the same instant, not by
what the data is.

## 4. The server sets the trade price

The browser could send the price it shows when you click, but then any request
could buy a Bitcoin for a dollar. Even an honest tab left open for five minutes
would send an outdated price. A trade request therefore carries only the
symbol, the direction and the quantity. The server uses its own live price, and
if it has no current price it refuses the trade (503) instead of guessing.

## 5. A trade can be closed exactly once

Closing a trade runs `UPDATE trade … WHERE closed_at IS NULL`, so only one of
two simultaneous clicks on "Close" can change the row. The other gets
`409 TRADE_ALREADY_CLOSED`, and the account is credited once. An integration
test fires two closes at the same moment against a real PostgreSQL to prove
it. A mock database couldn't show this, because the guarantee comes from the
database's row lock.

## 6. We rebuilt the trading model three days before delivery

Demo trading was first built as buy/sell ("spot"): only a sale has a result.
That meant a journal entry written when you *entered* a trade could never be
scored, and scoring your reasoning is the product's whole point. We rebuilt
trading as long and short positions, where every trade has an entry, an exit
and a result.

We kept the risk under control: the work happened on a separate branch, with a
go/no-go deadline after which we would have presented the old version. Losses
are capped at the margin, so you can never lose more than you put in. That
rule is simple to implement and right for a beginners' simulator.

On the way we found the browser recalculating profit and loss in floating-point
JavaScript. We moved it to the server, where money is calculated with exact
decimals.

## 7. Matching documents aren't proof

The mapping from chart range to candle size was stated identically in five
files, and it was wrong. The files had been copied from each other, and none
had been checked against the code. Similarly, a schema comment said candle
times "assume UTC". Twelve Data actually defaults to exchange-local time, so
instruments from different exchanges were stored on different clocks. The fix
was one request parameter, `timezone=UTC`.

**Lesson:** a statement like "assumes X" in a document is an open question,
not a fact.

## 8. A test that never runs checks nothing

The integration tests were written early but first ran in full near the end.
That first run immediately found two real bugs: a test setup that never
created the instrument a trade refers to, and an inconsistency in how the
journal saved a trade link. Diagrams went out of date the same way: nothing
fails when they are wrong. Diagrams are now generated from a script in the
repo, and GitHub Actions runs the tests on every push.
