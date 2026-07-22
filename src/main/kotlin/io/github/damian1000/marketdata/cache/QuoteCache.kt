package io.github.damian1000.marketdata.cache

import io.github.damian1000.marketdata.model.Quote
import io.github.damian1000.marketdata.source.QuoteSource
import io.github.damian1000.marketdata.source.QuoteUnavailable
import org.slf4j.LoggerFactory
import java.time.Duration
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
 * upstream blip never blanks a page a consumer is serving. Thread-safe: a refresh thread writes,
 * reader threads read.
 *
 * Two guards keep an unhealthy provider from being either hammered or trusted too long:
 * - **Negative cache:** a symbol that resolves to nothing and has no last-good mark is remembered
 *   for [negativeTtl], so repeated requests for the same unknown ticker don't each hit the provider.
 *   The set is LRU-bounded at [maxNegativeEntries] so free-text symbol entry can't grow it without
 *   bound. A symbol that later resolves clears its mark.
 * - **Max age:** when [maxAge] is set, [latest] stops offering a last-good mark once it is older than
 *   that, so a stale quote isn't served as current through a prolonged outage. [refresh]'s own
 *   return is the just-fetched (or last-good) value and is not age-filtered.
 */
class QuoteCache(
    private val source: QuoteSource,
    private val clock: () -> Instant = Instant::now,
    private val maxAge: Duration? = null,
    private val negativeTtl: Duration = DEFAULT_NEGATIVE_TTL,
    private val maxNegativeEntries: Int = DEFAULT_MAX_NEGATIVE_ENTRIES,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val snapshots = ConcurrentHashMap<String, CachedQuote>()
    private val misses =
        object : LinkedHashMap<String, Instant>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>): Boolean = size > maxNegativeEntries
        }

    /**
     * Fetches [symbol] and replaces its snapshot, returning the fresh one. On a provider failure the
     * stored snapshot is untouched and the last-good one is returned (null if none exists yet), so a
     * caller polling this never has to handle the failure itself. A symbol recently found unknown,
     * with no last-good mark, short-circuits to null without touching the provider (negative cache).
     */
    fun refresh(symbol: String): CachedQuote? {
        val lastGood = snapshots[symbol]
        if (lastGood == null && recentlyMissed(symbol)) return null
        return try {
            val cached = CachedQuote(source.latest(symbol), clock())
            snapshots[symbol] = cached
            clearMiss(symbol)
            cached
        } catch (e: QuoteUnavailable) {
            if (lastGood == null) recordMiss(symbol)
            log.warn("Quote refresh failed for {}; serving {}", symbol, if (lastGood == null) "nothing yet" else "last-good", e)
            lastGood
        }
    }

    /**
     * The last-good snapshot for [symbol], or null before its first successful [refresh] — or, when
     * [maxAge] is set, once that snapshot is older than [maxAge].
     */
    fun latest(symbol: String): CachedQuote? {
        val cached = snapshots[symbol] ?: return null
        if (maxAge != null && Duration.between(cached.fetchedAt, clock()) > maxAge) return null
        return cached
    }

    private fun recentlyMissed(symbol: String): Boolean =
        synchronized(misses) {
            val missedAt = misses[symbol] ?: return false
            val fresh = Duration.between(missedAt, clock()) <= negativeTtl
            if (!fresh) misses.remove(symbol)
            fresh
        }

    private fun recordMiss(symbol: String) {
        synchronized(misses) { misses[symbol] = clock() }
    }

    private fun clearMiss(symbol: String) {
        synchronized(misses) { misses.remove(symbol) }
    }

    companion object {
        /** How long a symbol that resolved to nothing is remembered before the provider is tried again. */
        val DEFAULT_NEGATIVE_TTL: Duration = Duration.ofMinutes(5)

        /** Upper bound on remembered unknown symbols, evicted least-recently-used past it. */
        const val DEFAULT_MAX_NEGATIVE_ENTRIES = 256
    }
}
