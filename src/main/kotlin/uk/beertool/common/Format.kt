package uk.beertool.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

const val MISSING = "—"

fun fmt(v: Double?, decimals: Int) = v?.let { "%.${decimals}f".format(it) } ?: MISSING

fun fmt(d: LocalDate?) = d?.toString() ?: MISSING

fun formatAmount(v: Double): String = BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

fun Double.asPercent() = this * 100.0

fun Double.roundTo(decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return (this * factor).roundToLong() / factor
}

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun shortDate(date: LocalDate, today: LocalDate): String = date.format(if (date.year == today.year) DAY_MONTH else DAY_MONTH_YEAR)

fun prettify(name: String) = name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
