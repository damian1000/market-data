package io.github.damian1000.marketdata.cache

import io.github.damian1000.marketdata.model.Quote
import io.github.damian1000.marketdata.source.QuoteSource
import io.github.damian1000.marketdata.source.QuoteUnavailable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A [quote] with the [fetchedAt] instant it was pulled from the provider — how stale the mark is. */
data class CachedQuote(
    val quote: Quote,
    val fetchedAt: Instant,
)

/**
 * The last-good snapshot in front of a [source]: [refresh] pulls a fresh quote and stores it, but a
 * provider failure leaves the previous snapshot in place rather than propagating — a transient
 * upstream blip never blanks a page a consumer is serving. [latest] is null only before the first
 * successful fetch. Thread-safe: a refresh thread writes, reader threads read.
 */
class QuoteCache(
    private val source: QuoteSource,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val snapshots = ConcurrentHashMap<String, CachedQuote>()

    /**
     * Fetches [symbol] and replaces its snapshot, returning the fresh one. On a provider failure the
     * stored snapshot is untouched and the last-good one is returned (null if none exists yet), so a
     * caller polling this never has to handle the failure itself.
     */
    fun refresh(symbol: String): CachedQuote? =
        try {
            val cached = CachedQuote(source.latest(symbol), clock())
            snapshots[symbol] = cached
            cached
        } catch (e: QuoteUnavailable) {
            val lastGood = snapshots[symbol]
            log.warn("Quote refresh failed for {}; serving {}", symbol, if (lastGood == null) "nothing yet" else "last-good", e)
            lastGood
        }

    /** The last-good snapshot for [symbol], or null before its first successful [refresh]. */
    fun latest(symbol: String): CachedQuote? = snapshots[symbol]
}
