package uk.beertool.batch

import uk.beertool.brewing.Calculators

data class BatchStats(
    val abv: Double?,
    val attenuation: Double?,
)

private fun attenuation(og: Double, fg: Double) =
    if (og <= 1.0) null else (og - fg) / (og - 1.0)

fun Batch.stats(): BatchStats {
    val og = measuredOg
    val fg = measuredFg
    return BatchStats(
        abv = abv,
        attenuation = if (og != null && fg != null) attenuation(og, fg) else null,
    )
}

fun measuredAbv(og: Double?, fg: Double?, lateAdditionAbv: Double): Double? {
    if (og == null || fg == null) return null
    return Calculators.abv(og, fg) + lateAdditionAbv
}
