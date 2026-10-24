package uk.beertool.web

import uk.beertool.brewing.gravityReading
import uk.beertool.common.formatAmount

class InvalidInput(message: String) : RuntimeException(message)

// Each range keeps a value inside its DB column and away from a divide by zero.
internal val VOLUME_L = 0.1..1000.0
internal val LOSS_L = 0.0..1000.0
internal val PERCENT = 0.0..100.0
internal val MINUTES = 0..1440
internal val TEMP_C = 0.0..100.0
internal val GRAIN_KG = 0.0..1000.0
internal val COLOUR_EBC = 0.0..2000.0
internal val HOP_G = 0.0..10_000.0
internal val ALPHA_ACID = 0.0..50.0
internal val DRY_HOP_DAYS = 0..60
internal val EXTRA_AMOUNT = 0.0..100_000.0
internal val WATER_ADDITION = 0.0..1000.0
internal val ION_PPM = 0.0..5000.0
internal val L_PER_KG = 0.0..10.0

internal fun Double?.within(label: String, range: ClosedFloatingPointRange<Double>): Double? = this?.also {
    if (it !in range) throw InvalidInput("$label must be between ${formatAmount(range.start)} and ${formatAmount(range.endInclusive)}.")
}

internal fun Int?.within(label: String, range: IntRange): Int? = this?.also {
    if (it !in range) throw InvalidInput("$label must be between ${range.first} and ${range.last}.")
}

internal fun Double?.gravity(label: String): Double? = this?.let {
    gravityReading(it) ?: throw InvalidInput("That $label doesn't look like a gravity. Enter it as 1.052 or 1052.")
}
