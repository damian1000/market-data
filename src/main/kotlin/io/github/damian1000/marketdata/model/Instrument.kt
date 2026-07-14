package io.github.damian1000.marketdata.model

/**
 * A tradable listing's identity: its ticker [symbol], the issuer [name], the [currency] it trades
 * in, and the [exchange] it lists on. Reference data — it changes rarely, unlike the [Quote] that
 * marks it. A value type; there is no alternative implementation to abstract behind an interface.
 */
data class Instrument(
    val symbol: String,
    val name: String,
    val currency: String,
    val exchange: String,
) {
    init {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(currency.isNotBlank()) { "currency must not be blank" }
    }
}
