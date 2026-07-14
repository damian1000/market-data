package io.github.damian1000.marketdata.cache

import io.github.damian1000.marketdata.model.Instrument
import io.github.damian1000.marketdata.model.Quote
import io.github.damian1000.marketdata.source.QuoteSource
import io.github.damian1000.marketdata.source.QuoteUnavailable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class QuoteCacheTest {
    private val apple = Instrument("AAPL", "Apple Inc.", "USD", "NasdaqGS")

    private fun quoteAt(last: String) =
        Quote(apple, BigDecimal(last), BigDecimal("100"), BigDecimal(last), BigDecimal(last), Instant.EPOCH, true)

    /** A source the test scripts: each call returns the next queued result, throwing where queued. */
    private class ScriptedSource(
        private val results: ArrayDeque<Result<Quote>>,
    ) : QuoteSource {
        var calls = 0
            private set

        override fun latest(symbol: String): Quote {
            calls++
            return results.removeFirst().getOrThrow()
        }
    }

    @Test
    fun `latest is null before the first refresh`() {
        val cache = QuoteCache(ScriptedSource(ArrayDeque()))
        assertNull(cache.latest("AAPL"))
    }

    @Test
    fun `refresh stores the fetched quote stamped with the clock`() {
        val quote = quoteAt("110")
        val at = Instant.parse("2026-07-14T15:42:00Z")
        val cache = QuoteCache(ScriptedSource(ArrayDeque(listOf(Result.success(quote)))), clock = { at })

        val cached = cache.refresh("AAPL")

        assertEquals(quote, cached?.quote)
        assertEquals(at, cached?.fetchedAt)
        assertSame(cached, cache.latest("AAPL"))
    }

    @Test
    fun `a provider failure leaves the last-good snapshot in place`() {
        val good = quoteAt("110")
        val source =
            ScriptedSource(
                ArrayDeque(listOf(Result.success(good), Result.failure(QuoteUnavailable("Yahoo down")))),
            )
        val cache = QuoteCache(source)

        val first = cache.refresh("AAPL")
        val afterFailure = cache.refresh("AAPL")

        assertEquals(good, first?.quote)
        assertSame(first, afterFailure)
        assertSame(first, cache.latest("AAPL"))
        assertEquals(2, source.calls)
    }

    @Test
    fun `a failure before any success returns null`() {
        val source = ScriptedSource(ArrayDeque(listOf(Result.failure(QuoteUnavailable("Yahoo down")))))
        val cache = QuoteCache(source)

        assertNull(cache.refresh("AAPL"))
        assertNull(cache.latest("AAPL"))
    }
}
