package io.github.damian1000.marketdata.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import java.math.BigDecimal

/**
 * A single real hit against Yahoo, on by default so a change to the provider path can actually go
 * red — a loopback fake proves parsing, not that Yahoo still answers in the shape we parse. Skip it
 * in an offline build with `MARKET_DATA_LIVE_SKIP=true` (an environment variable, not a `-D` system
 * property — a forked test JVM inherits the former, not the latter).
 */
@DisabledIfEnvironmentVariable(named = "MARKET_DATA_LIVE_SKIP", matches = "true")
class YahooQuoteSourceLiveTest {
    @Test
    fun `fetches a live quote for a well-known symbol`() {
        val quote = YahooQuoteSource().latest("AAPL")

        assertEquals("AAPL", quote.symbol)
        assertTrue(quote.instrument.name.isNotBlank(), "expected an issuer name")
        assertEquals("USD", quote.instrument.currency)
        assertTrue(quote.last > BigDecimal.ZERO, "expected a positive last price")
        assertTrue(quote.previousClose > BigDecimal.ZERO, "expected a positive previous close")
    }
}
