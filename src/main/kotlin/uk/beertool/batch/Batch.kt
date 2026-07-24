package uk.beertool.batch

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
) {
    val isOnBrewDay get() = packagedDate == null && measuredOg == null
}
