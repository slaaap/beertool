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

fun Batch.toDraft() = NewBatch(
    recipeId = recipeId,
    brewDate = brewDate,
    packagedDate = packagedDate,
    measuredOg = measuredOg,
    measuredFg = measuredFg,
    measuredPreBoilVolumeL = measuredPreBoilVolumeL,
    measuredPostBoilVolumeL = measuredPostBoilVolumeL,
    measuredFermenterVolumeL = measuredFermenterVolumeL,
    notes = notes,
)
