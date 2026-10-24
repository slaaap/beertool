package uk.beertool.brewing

const val MIN_GRAVITY = 0.98
const val MAX_GRAVITY = 1.2

// Brewers write gravity both ways, as 1.052 or as 1052 gravity points.
fun gravityReading(value: Double): Double? = when (value) {
    in MIN_GRAVITY..MAX_GRAVITY -> value
    in MIN_GRAVITY * 1000..MAX_GRAVITY * 1000 -> value / 1000
    else -> null
}
