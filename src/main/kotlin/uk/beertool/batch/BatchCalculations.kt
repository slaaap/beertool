package uk.beertool.batch

import uk.beertool.brewing.Calculators

val Batch.attenuation: Double?
    get() {
        val og = measuredOg ?: return null
        val fg = measuredFg ?: return null
        return if (og <= 1.0) null else (og - fg) / (og - 1.0)
    }

fun measuredAbv(og: Double?, fg: Double?, lateAdditionAbv: Double): Double? {
    if (og == null || fg == null) return null
    return Calculators.abv(og, fg) + lateAdditionAbv
}
