package io.github.damian1000.marketdata.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * A mark for an [instrument] at a point in time: the [last] traded price, the [previousClose] the
 * day's move is measured from, the session [dayHigh]/[dayLow], and the [asOf] instant the price
 * was stamped. [marketOpen] is whether the regular session was trading when this was sourced.
 *
 * Prices are [BigDecimal] so the value carried is exactly the one the provider quoted — no binary
 * float drift into a number a consumer displays or seeds a book from.
 */
data class Quote(
    val instrument: Instrument,
    val last: BigDecimal,
    val previousClose: BigDecimal,
    val dayHigh: BigDecimal,
    val dayLow: BigDecimal,
    val asOf: Instant,
    val marketOpen: Boolean,
) {
    init {
        require(last.signum() > 0) { "last must be positive, got $last" }
        require(previousClose.signum() > 0) { "previousClose must be positive, got $previousClose" }
    }

    val symbol: String get() = instrument.symbol

    /** Signed move since the previous close: positive up on the day, negative down. */
    val change: BigDecimal get() = last - previousClose

    /** [change] as a percentage of the previous close, to two decimal places. */
    val changePercent: BigDecimal
        get() = change.divide(previousClose, 6, RoundingMode.HALF_UP).movePointRight(2).setScale(2, RoundingMode.HALF_UP)
}
