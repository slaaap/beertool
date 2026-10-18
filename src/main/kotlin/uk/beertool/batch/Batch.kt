package uk.beertool.batch

import uk.beertool.recipe.WaterSnapshot
import java.time.Instant
import java.time.LocalDate

data class Batch(
    val id: Long,
    val recipeId: Long,
    val userId: Long,
    val no: Int = 0,
    val brewDate: LocalDate?,

    val packagedDate: LocalDate?,
    val measuredOg: Double?,
    val measuredFg: Double?,

    val measuredPreBoilVolumeL: Double? = null,
    val measuredPostBoilVolumeL: Double? = null,
    val measuredFermenterVolumeL: Double? = null,

    val notes: String? = null,

    val abv: Double?,

    val mashEfficiency: Double?,
    val createdAt: Instant,
    val water: WaterSnapshot? = null,
) {

    val isPackaged get() = packagedDate != null

    val isOnBrewDay get() = !isPackaged && measuredOg == null

    val isFermenting get() = !isPackaged && !isOnBrewDay

    val waterLocked get() = !isOnBrewDay
}
