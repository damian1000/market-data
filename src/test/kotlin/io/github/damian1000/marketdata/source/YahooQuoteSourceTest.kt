package io.github.damian1000.marketdata.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant

class YahooQuoteSourceTest {
    private lateinit var server: HttpServer
    private var handler: (HttpExchange) -> Unit = { notFound(it) }

    private val source by lazy { YahooQuoteSource("http://localhost:${server.address.port}") }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { handler(it) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `parses the instrument and quote from a chart response`() {
        handler = respondJson(200, chartMeta(marketTime = 1_000_150))

        val quote = source.latest("AAPL")

        assertEquals("AAPL", quote.symbol)
        assertEquals("Apple Inc.", quote.instrument.name)
        assertEquals("USD", quote.instrument.currency)
        assertEquals("NasdaqGS", quote.instrument.exchange)
        assertEquals(BigDecimal("317.31"), quote.last)
        assertEquals(BigDecimal("315.32"), quote.previousClose)
        assertEquals(BigDecimal("323.45"), quote.dayHigh)
        assertEquals(BigDecimal("315.78"), quote.dayLow)
        assertEquals(Instant.ofEpochSecond(1_000_150), quote.asOf)
    }

    @Test
    fun `reads the market as open when the last trade is inside the session window`() {
        handler = respondJson(200, chartMeta(marketTime = 1_000_150)) // start 1_000_000, end 1_050_000
        assertTrue(source.latest("AAPL").marketOpen)
    }

    @Test
    fun `reads the market as closed when the last trade is past the session close`() {
        handler = respondJson(200, chartMeta(marketTime = 1_060_000))
        assertFalse(source.latest("AAPL").marketOpen)
    }

    @Test
    fun `reads the market as closed when there is no trading period`() {
        handler = respondJson(200, chartMeta(tradingPeriod = null))
        assertFalse(source.latest("AAPL").marketOpen)
    }

    @Test
    fun `reads the market as closed when the session window is incomplete`() {
        handler = respondJson(200, chartMeta(tradingPeriod = """{"regular":{"end":1050000}}"""))
        assertFalse(source.latest("AAPL").marketOpen)
    }

    @Test
    fun `falls back to the short name when there is no long name`() {
        handler = respondJson(200, chartMeta(longName = null, shortName = "Apple"))
        assertEquals("Apple", source.latest("AAPL").instrument.name)
    }

    @Test
    fun `falls back to the symbol when the response names no issuer`() {
        handler = respondJson(200, chartMeta(longName = null, shortName = null))
        assertEquals("AAPL", source.latest("AAPL").instrument.name)
    }

    @Test
    fun `falls back to the plain previous close when there is no chart previous close`() {
        handler = respondJson(200, chartMeta(chartPreviousClose = null, previousClose = "315.32"))
        assertEquals(BigDecimal("315.32"), source.latest("AAPL").previousClose)
    }

    @Test
    fun `treats an unknown symbol (Yahoo 404 error body) as unavailable`() {
        handler = respondJson(404, errorBody("Not Found", "No data found, symbol may be delisted"))
        assertThrows<QuoteUnavailable> { source.latest("NOPE") }
    }

    @Test
    fun `treats an error carried in a 200 body as unavailable`() {
        handler = respondJson(200, """{"chart":{"result":null,"error":{"code":"Bad Request"}}}""")
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a non-200 as unavailable`() {
        handler = respondJson(500, "{}")
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a body with no chart object as unavailable`() {
        handler = respondJson(200, "{}")
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a result carrying no meta as unavailable`() {
        handler = respondJson(200, """{"chart":{"result":[{}],"error":null}}""")
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats an empty result array as unavailable`() {
        handler = respondJson(200, """{"chart":{"result":[],"error":null}}""")
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a non-JSON body as unavailable`() {
        handler = { exchange -> writeBody(exchange, 200, "<html>rate limited</html>") }
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a JSON body that is not an object as unavailable`() {
        handler = { exchange -> writeBody(exchange, 200, "123") }
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a missing price field as unavailable`() {
        handler = respondJson(200, chartMeta(includePrice = false))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a missing previous close as unavailable`() {
        handler = respondJson(200, chartMeta(chartPreviousClose = null, previousClose = null))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a missing market time as unavailable`() {
        handler = respondJson(200, chartMeta(marketTime = null))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a missing currency as unavailable`() {
        handler = respondJson(200, chartMeta(currency = null))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats a connection failure as unavailable`() {
        val dead = YahooQuoteSource("http://localhost:${server.address.port}")
        server.stop(0)
        assertThrows<QuoteUnavailable> { dead.latest("AAPL") }
    }

    @Test
    fun `falls back to an empty exchange when neither exchange field is present`() {
        handler = respondJson(200, chartMeta(explicitNull = setOf("fullExchangeName", "exchangeName")))
        assertEquals("", source.latest("AAPL").instrument.exchange)
    }

    @Test
    fun `treats an explicit JSON null symbol the same as a missing one`() {
        handler = respondJson(200, chartMeta(explicitNull = setOf("symbol")))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `treats an explicit JSON null currency the same as a missing one`() {
        handler = respondJson(200, chartMeta(explicitNull = setOf("currency")))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `falls back to the short name when the long name is an explicit JSON null`() {
        handler = respondJson(200, chartMeta(explicitNull = setOf("longName")))
        assertEquals("Apple Inc.", source.latest("AAPL").instrument.name)
    }

    @Test
    fun `treats an explicit JSON null price the same as a missing one`() {
        handler = respondJson(200, chartMeta(explicitNull = setOf("regularMarketPrice")))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `falls back to the plain previous close when the chart one is an explicit JSON null`() {
        handler = respondJson(200, chartMeta(explicitNull = setOf("chartPreviousClose"), previousClose = "315.32"))
        assertEquals(BigDecimal("315.32"), source.latest("AAPL").previousClose)
    }

    @Test
    fun `treats an explicit JSON null market time the same as a missing one`() {
        handler = respondJson(200, chartMeta(explicitNull = setOf("regularMarketTime")))
        assertThrows<QuoteUnavailable> { source.latest("AAPL") }
    }

    @Test
    fun `reads the market as closed when the session start is an explicit JSON null`() {
        handler = respondJson(200, chartMeta(tradingPeriod = """{"regular":{"start":null,"end":1050000}}"""))
        assertFalse(source.latest("AAPL").marketOpen)
    }

    @Test
    fun `reads the market as closed when the session end is an explicit JSON null`() {
        handler = respondJson(200, chartMeta(tradingPeriod = """{"regular":{"start":1000000,"end":null}}"""))
        assertFalse(source.latest("AAPL").marketOpen)
    }

    private fun respondJson(
        status: Int,
        body: String,
    ): (HttpExchange) -> Unit = { exchange -> writeBody(exchange, status, body) }

    private fun writeBody(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun notFound(exchange: HttpExchange) = writeBody(exchange, 404, errorBody("Not Found", "no route"))

    private fun errorBody(
        code: String,
        description: String,
    ): String = """{"chart":{"result":null,"error":{"code":"$code","description":"$description"}}}"""

    @Suppress("LongParameterList")
    private fun chartMeta(
        symbol: String? = "AAPL",
        currency: String? = "USD",
        longName: String? = "Apple Inc.",
        shortName: String? = "Apple Inc.",
        includePrice: Boolean = true,
        chartPreviousClose: String? = "315.32",
        previousClose: String? = null,
        marketTime: Long? = 1_000_150,
        tradingPeriod: String? = """{"regular":{"start":1000000,"end":1050000}}""",
        // Fields named here are emitted as a literal JSON null, distinct from omitting the key
        // entirely — the parser's `?.takeIf { !it.isJsonNull }` branch only fires for the former.
        explicitNull: Set<String> = emptySet(),
    ): String {
        val literalFields = mutableListOf<String>()

        fun field(
            name: String,
            value: String?,
            quoted: Boolean = true,
        ) {
            when {
                name in explicitNull -> literalFields.add(""""$name":null""")
                value == null -> return
                quoted -> literalFields.add(""""$name":"$value"""")
                else -> literalFields.add(""""$name":$value""")
            }
        }
        field("symbol", symbol)
        field("currency", currency)
        field("fullExchangeName", "NasdaqGS")
        field("exchangeName", "NMS")
        field("longName", longName)
        field("shortName", shortName)
        if (includePrice) field("regularMarketPrice", "317.31", quoted = false)
        field("chartPreviousClose", chartPreviousClose, quoted = false)
        field("previousClose", previousClose, quoted = false)
        field("regularMarketDayHigh", "323.45", quoted = false)
        field("regularMarketDayLow", "315.78", quoted = false)
        field("regularMarketTime", marketTime?.toString(), quoted = false)
        field("currentTradingPeriod", tradingPeriod, quoted = false)
        return """{"chart":{"result":[{"meta":{${literalFields.joinToString(",")}}}],"error":null}}"""
    }
}
