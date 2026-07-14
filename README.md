# Market Data

[![CI](https://github.com/damian1000/market-data/actions/workflows/ci.yml/badge.svg)](https://github.com/damian1000/market-data/actions/workflows/ci.yml)
[![CodeQL](https://github.com/damian1000/market-data/actions/workflows/codeql.yml/badge.svg)](https://github.com/damian1000/market-data/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/damian1000/market-data/graph/badge.svg)](https://codecov.io/gh/damian1000/market-data)

Real instrument reference and quotes for the trading estate: fetch a symbol's mark from a live
provider and serve the last-good snapshot, so a consumer can anchor to a real price — the
[live order book](https://github.com/damian1000/orderbook) opens its book around the actual last
trade in AAPL rather than a synthetic seed. A library, pulled in via JitPack; it runs in the
consumer's process, not as a service of its own.

## Design

- **`Instrument` is identity, `Quote` is the mark.** `Instrument` (symbol, issuer name, currency,
  exchange) is reference data that rarely changes; `Quote` carries the moving numbers — last,
  previous close, day high/low, the `asOf` instant, and whether the regular session was trading.
  Both are `BigDecimal`-priced value types, so the number a consumer displays or seeds a book from
  is exactly the one the provider quoted.
- **`QuoteSource` is the provider seam.** A one-method interface (`latest(symbol): Quote`) that a
  cache or consumer depends on, so the provider is swappable and fakeable. `YahooQuoteSource` is
  the implementation.
- **`QuoteCache` serves the last-good snapshot.** `refresh(symbol)` pulls a fresh quote and stores
  it; a provider failure leaves the previous snapshot in place and returns it, so a transient
  upstream blip never blanks a page the consumer is serving. `latest(symbol)` is null only before
  the first successful fetch. The consumer owns the refresh schedule; the cache owns being warm.

## Provider

`YahooQuoteSource` reads Yahoo Finance's public `chart` endpoint. A single GET returns a `meta`
block with the issuer name, currency, exchange, last price, previous close, and the regular
session's open/close times — enough to build both the `Instrument` and the `Quote` from one call,
and without the cookie-and-crumb exchange the `quoteSummary` API gates behind. The response is
parsed strictly: a missing or wrongly-typed field is a malformed provider response
(`QuoteUnavailable`), not a value to guess at. Request and connect timeouts are bounded, so one
hung socket fails the fetch rather than stalling the caller.

The `chart` payload is a delayed/last-trade mark, not a live tick feed; the `asOf` instant travels
with every quote so a consumer can show how fresh it is.

## Failure handling

- An unknown or delisted symbol (Yahoo answers `404` with an error body), a non-`200`, a non-JSON
  body, or a missing required field all surface as `QuoteUnavailable`.
- `QuoteCache` turns that into resilience: the fetch failing does not disturb the stored snapshot,
  and the caller keeps getting the last good mark.

The loopback test replicates the provider's error gate — it answers `404` with Yahoo's own error
shape for an unknown symbol — so the client's failure paths are exercised, not just its happy path.
A single live test (`YahooQuoteSourceLiveTest`, on by default, skip with `MARKET_DATA_LIVE_SKIP=true`)
hits the real endpoint, so a change that the fake still accepts but Yahoo no longer serves goes red.

## Run

```bash
./gradlew run                          # prints a live AAPL quote
MARKET_DATA_SYMBOL=MSFT ./gradlew run  # any symbol
```

```
Apple Inc. (AAPL) on NasdaqGS
  last 317.31 USD  (1.99 / 0.63%)  prev close 315.32
  day 315.78–323.45  market closed  as of 2026-07-14T20:00:01Z
```

## Use it

```kotlin
val cache = QuoteCache(YahooQuoteSource())

// On a schedule the consumer owns:
cache.refresh("AAPL")

// Wherever the consumer needs the current mark:
val cached = cache.latest("AAPL") ?: return
val quote = cached.quote
println("${quote.instrument.name}: ${quote.last} ${quote.instrument.currency}")
```

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.damian1000:market-data:v1.0.0' }
```

## Build

```bash
./gradlew spotlessCheck   # ktlint + Prettier (config/docs)
./gradlew clean check     # tests + 90% instruction coverage gate
```
