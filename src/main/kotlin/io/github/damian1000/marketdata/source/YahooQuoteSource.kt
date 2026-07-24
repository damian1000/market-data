package io.github.damian1000.marketdata.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import io.github.damian1000.marketdata.model.Instrument
import io.github.damian1000.marketdata.model.Quote
import java.math.BigDecimal
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** Thrown when a provider cannot supply a usable quote for a symbol — HTTP error, or malformed body. */
class QuoteUnavailable(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Where a live [Quote] comes from. The seam a cache or consumer depends on, not the provider. */
fun interface QuoteSource {
    /** The latest mark for [symbol], or throws [QuoteUnavailable] if the provider can't supply one. */
    fun latest(symbol: String): Quote
}

/**
 * A [QuoteSource] over Yahoo Finance's public `chart` endpoint. One GET returns a `meta` block with
 * the instrument's name, currency, exchange, last price, previous close, and session times — enough
 * to build both the [Instrument] identity and the [Quote] mark without the cookie/crumb dance the
 * `quoteSummary` API needs.
 *
 * The response is parsed strictly: a missing or wrongly-typed field is a malformed provider
 * response ([QuoteUnavailable]), not a value to guess at. [baseUrl] and [httpClient] are injectable
 * so a test can drive the parser against a loopback server.
 */
class YahooQuoteSource(
    private val baseUrl: String = "https://query1.finance.yahoo.com",
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault()) // honour a system proxy where one is set; DIRECT otherwise
            .build(),
) : QuoteSource {
    // The boundary catch converts any parse-time surprise (a field of the wrong JSON type throws
    // Gson's own runtime exceptions, not ours) into the one exception type the contract names.
    override fun latest(symbol: String): Quote =
        try {
            buildQuote(symbol)
        } catch (e: QuoteUnavailable) {
            throw e
        } catch (e: RuntimeException) {
            throw QuoteUnavailable("Yahoo returned a malformed response for $symbol", e)
        }

    private fun buildQuote(symbol: String): Quote {
        val meta = fetchMeta(symbol)
        val instrument =
            Instrument(
                symbol = str(meta, "symbol"),
                name = firstString(meta, "longName", "shortName") ?: symbol,
                currency = str(meta, "currency"),
                exchange = firstString(meta, "fullExchangeName", "exchangeName") ?: "",
            )
        return Quote(
            instrument = instrument,
            last = decimal(meta, "regularMarketPrice"),
            previousClose = firstDecimal(meta, "chartPreviousClose", "previousClose"),
            dayHigh = decimal(meta, "regularMarketDayHigh"),
            dayLow = decimal(meta, "regularMarketDayLow"),
            asOf = Instant.ofEpochSecond(long(meta, "regularMarketTime")),
            marketOpen = marketOpen(meta),
        )
    }

    private fun fetchMeta(symbol: String): JsonObject {
        val body = get("$baseUrl/v8/finance/chart/${encodePathSegment(symbol)}?interval=1d&range=1d")
        val root =
            try {
                JsonParser.parseString(body).asJsonObject
            } catch (e: JsonSyntaxException) {
                throw QuoteUnavailable("Yahoo returned a non-JSON body for $symbol", e)
            } catch (e: IllegalStateException) {
                throw QuoteUnavailable("Yahoo returned a non-JSON body for $symbol", e)
            }
        val chart = root.getAsJsonObject("chart") ?: throw QuoteUnavailable("no chart in response for $symbol")
        chart.get("error")?.takeIf { !it.isJsonNull }?.let {
            throw QuoteUnavailable("Yahoo reported an error for $symbol: $it")
        }
        val results = chart.getAsJsonArray("result")
        if (results == null || results.isEmpty) {
            throw QuoteUnavailable("Yahoo returned no result for $symbol")
        }
        return results[0].asJsonObject.getAsJsonObject("meta")
            ?: throw QuoteUnavailable("Yahoo result carried no meta for $symbol")
    }

    private fun get(url: String): String {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                throw QuoteUnavailable("Yahoo request failed for $url", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw QuoteUnavailable("Yahoo request interrupted for $url", e)
            }
        if (response.statusCode() != 200) {
            throw QuoteUnavailable("Yahoo returned HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }

    // Yahoo stamps the last trade time and the regular session's open/close bounds; the market is
    // trading when that trade time falls inside the session window this payload describes.
    private fun marketOpen(meta: JsonObject): Boolean {
        val regular =
            meta.getAsJsonObject("currentTradingPeriod")?.getAsJsonObject("regular") ?: return false
        val start = regular.get("start")?.takeIf { !it.isJsonNull }?.asLong ?: return false
        val end = regular.get("end")?.takeIf { !it.isJsonNull }?.asLong ?: return false
        val marketTime = long(meta, "regularMarketTime")
        return marketTime in start until end
    }

    private fun str(
        meta: JsonObject,
        field: String,
    ): String = meta.get(field)?.takeIf { !it.isJsonNull }?.asString ?: throw missing(field)

    private fun firstString(
        meta: JsonObject,
        vararg fields: String,
    ): String? = fields.firstNotNullOfOrNull { meta.get(it)?.takeIf { v -> !v.isJsonNull }?.asString }

    private fun decimal(
        meta: JsonObject,
        field: String,
    ): BigDecimal = meta.get(field)?.takeIf { !it.isJsonNull }?.asBigDecimal ?: throw missing(field)

    private fun firstDecimal(
        meta: JsonObject,
        vararg fields: String,
    ): BigDecimal =
        fields.firstNotNullOfOrNull { meta.get(it)?.takeIf { v -> !v.isJsonNull }?.asBigDecimal }
            ?: throw missing(fields.joinToString("/"))

    private fun long(
        meta: JsonObject,
        field: String,
    ): Long = meta.get(field)?.takeIf { !it.isJsonNull }?.asLong ?: throw missing(field)

    // The symbol is interpolated into the request path, so it is encoded as a single path segment:
    // a direct library caller isn't bound by the registry's symbol validation, and an unescaped '/'
    // or '?' would otherwise reshape the request into a different path or smuggle query parameters.
    // URLEncoder leaves '.' untouched, so a share class like BRK.B stays intact; its one path-unsafe
    // quirk — encoding space as '+' (form semantics) — is corrected to %20 for a path segment.
    private fun encodePathSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun missing(field: String) = QuoteUnavailable("Yahoo meta was missing $field")

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
