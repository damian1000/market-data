package io.github.damian1000.marketdata.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class QuoteTest {
    private val apple = Instrument("AAPL", "Apple Inc.", "USD", "NasdaqGS")

    private fun quote(
        last: String,
        previousClose: String,
    ) = Quote(
        instrument = apple,
        last = BigDecimal(last),
        previousClose = BigDecimal(previousClose),
        dayHigh = BigDecimal(last),
        dayLow = BigDecimal(last),
        asOf = Instant.EPOCH,
        marketOpen = true,
    )

    @Test
    fun `takes its symbol from the instrument`() {
        assertEquals("AAPL", quote("110", "100").symbol)
    }

    @Test
    fun `change is the signed move since the previous close`() {
        assertEquals(BigDecimal("10.00"), quote("110.00", "100.00").change)
        assertEquals(BigDecimal("-5.00"), quote("95.00", "100.00").change)
    }

    @Test
    fun `changePercent is the move as a percentage of the previous close`() {
        assertEquals(BigDecimal("10.00"), quote("110.00", "100.00").changePercent)
        assertEquals(BigDecimal("-2.50"), quote("97.50", "100.00").changePercent)
    }

    @Test
    fun `changePercent rounds to two places`() {
        // 1.99 / 315.32 = 0.006311... -> 0.63%
        assertEquals(BigDecimal("0.63"), quote("317.31", "315.32").changePercent)
    }

    @Test
    fun `rejects a non-positive last`() {
        assertThrows(IllegalArgumentException::class.java) { quote("0", "100") }
    }

    @Test
    fun `rejects a non-positive previous close`() {
        assertThrows(IllegalArgumentException::class.java) { quote("100", "0") }
    }
}
