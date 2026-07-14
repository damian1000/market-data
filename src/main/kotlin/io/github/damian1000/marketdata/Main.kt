package io.github.damian1000.marketdata

import io.github.damian1000.marketdata.cache.QuoteCache
import io.github.damian1000.marketdata.source.YahooQuoteSource

/**
 * Prints a live quote for one symbol (default AAPL, or `MARKET_DATA_SYMBOL`) and exits — a hand-run
 * check that the provider path works end to end. The real consumer is the order book, which holds
 * the [QuoteCache] open and refreshes it on a schedule.
 */
fun main() {
    val symbol = System.getenv("MARKET_DATA_SYMBOL")?.takeIf { it.isNotBlank() } ?: "AAPL"
    val cached = QuoteCache(YahooQuoteSource()).refresh(symbol) ?: error("No quote available for $symbol")
    val q = cached.quote
    val session = if (q.marketOpen) "open" else "closed"
    println("${q.instrument.name} (${q.symbol}) on ${q.instrument.exchange}")
    println("  last ${q.last} ${q.instrument.currency}  (${q.change} / ${q.changePercent}%)  prev close ${q.previousClose}")
    println("  day ${q.dayLow}–${q.dayHigh}  market $session  as of ${q.asOf}")
}
