package io.github.damian1000.marketdata.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InstrumentTest {
    @Test
    fun `carries its identity fields`() {
        val apple = Instrument("AAPL", "Apple Inc.", "USD", "NasdaqGS")
        assertEquals("AAPL", apple.symbol)
        assertEquals("Apple Inc.", apple.name)
        assertEquals("USD", apple.currency)
        assertEquals("NasdaqGS", apple.exchange)
    }

    @Test
    fun `rejects a blank symbol`() {
        assertThrows(IllegalArgumentException::class.java) {
            Instrument(" ", "Apple Inc.", "USD", "NasdaqGS")
        }
    }

    @Test
    fun `rejects a blank currency`() {
        assertThrows(IllegalArgumentException::class.java) {
            Instrument("AAPL", "Apple Inc.", "", "NasdaqGS")
        }
    }
}
