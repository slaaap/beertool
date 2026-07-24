package uk.beertool.recipe

import kotlinx.serialization.Serializable
import java.time.Instant

data class Recipe(
    val id: Long,
    val userId: Long,
    val no: Int = 0,
    val name: String,
    val style: String?,
    val description: String?,

    val preBoilVolumeL: Double?,

    val postBoilVolumeL: Double,

    val fermenterVolumeL: Double,

    val efficiency: Double,
    val boilTimeMin: Int,

    val mashSteps: List<MashStep>,
    val fermentables: List<RecipeFermentable>,
    val hops: List<RecipeHop>,
    val yeasts: List<RecipeYeast>,
    val extras: List<RecipeExtra>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class MashStep(
    val tempC: Double,
    val timeMin: Int,
)

data class RecipeFermentable(
    val name: String,
    val amountKg: Double,
    val type: FermentableType = FermentableType.default,
    val colourEbc: Double? = null,
    val extractPercent: Double? = null,

    val usage: FermentableUsage = FermentableUsage.default,

    val boilTimeMin: Int? = null,
)

data class RecipeHop(
    val name: String,
    val amountG: Double,
    val alphaAcid: Double,
    val usage: HopUsage = HopUsage.default,
    val boilTimeMin: Int? = null,
)

data class RecipeYeast(
    val name: String,
    val attenuation: Double = DEFAULT_ATTENUATION,
    val usage: YeastUsage = YeastUsage.default,
)

data class RecipeExtra(
    val name: String,
    val amount: Double,
    val unit: AmountUnit = AmountUnit.default,
    val usage: ExtraUsage = ExtraUsage.default,
    val boilTimeMin: Int? = null,
)
