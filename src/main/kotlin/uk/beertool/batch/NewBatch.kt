package uk.beertool.batch

import java.time.LocalDate

data class NewBatch(
    val recipeId: Long,
    val brewDate: LocalDate? = null,
    val packagedDate: LocalDate? = null,
    val measuredOg: Double? = null,
    val measuredFg: Double? = null,
    val measuredPreBoilVolumeL: Double? = null,
    val measuredPostBoilVolumeL: Double? = null,
    val measuredFermenterVolumeL: Double? = null,
    val notes: String? = null,
)
