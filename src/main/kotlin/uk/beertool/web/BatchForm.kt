package uk.beertool.web

import kotlinx.serialization.Serializable
import uk.beertool.batch.NewBatch
import java.time.LocalDate

@Serializable
data class BatchForm(
    @Serializable(with = BlankAsNullDate::class) val brewDate: LocalDate? = null,
    @Serializable(with = BlankAsNullDate::class) val packagedDate: LocalDate? = null,
    @Serializable(with = BlankAsNullDouble::class) val measuredOg: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val measuredFg: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val measuredPreBoilVolumeL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val measuredPostBoilVolumeL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val measuredFermenterVolumeL: Double? = null,
    val notes: String = "",
)

fun BatchForm.toNewBatch(recipeId: Long): NewBatch = NewBatch(
    recipeId = recipeId,
    brewDate = brewDate,
    packagedDate = packagedDate,
    measuredOg = measuredOg,
    measuredFg = measuredFg,
    measuredPreBoilVolumeL = measuredPreBoilVolumeL,
    measuredPostBoilVolumeL = measuredPostBoilVolumeL,
    measuredFermenterVolumeL = measuredFermenterVolumeL,
    notes = notes.trim().ifBlank { null },
)
