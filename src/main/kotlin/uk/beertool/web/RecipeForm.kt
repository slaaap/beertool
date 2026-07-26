package uk.beertool.web

import kotlinx.serialization.Serializable
import uk.beertool.recipe.AmountUnit
import uk.beertool.recipe.BrewStage
import uk.beertool.recipe.DEFAULT_ATTENUATION
import uk.beertool.recipe.ExtraUsage
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeExtra
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.RecipeYeast
import uk.beertool.recipe.YeastUsage
import uk.beertool.user.BrewerPreferences

@Serializable
data class RecipeForm(
    val name: String = "",
    val style: String = "",
    val description: String = "",
    @Serializable(with = BlankAsNullDouble::class) val preBoilVolumeL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val postBoilVolumeL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val fermenterVolumeL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val efficiency: Double? = null,
    @Serializable(with = BlankAsNullInt::class) val boilTimeMin: Int? = null,
    val mashSteps: List<MashStepRow> = emptyList(),
    val fermentables: List<FermentableRow> = emptyList(),
    val hops: List<HopRow> = emptyList(),
    val yeasts: List<YeastRow> = emptyList(),
    val extras: List<ExtraRow> = emptyList(),
)

@Serializable
data class MashStepRow(
    @Serializable(with = BlankAsNullDouble::class) val tempC: Double? = null,
    @Serializable(with = BlankAsNullInt::class) val timeMin: Int? = null,
)

sealed interface FormRow {
    val name: String
}

@Serializable
data class FermentableRow(
    override val name: String = "",
    @Serializable(with = BlankAsNullDouble::class) val amountKg: Double? = null,
    @Serializable(with = FermentableTypeCode::class) val type: FermentableType = FermentableType.default,
    @Serializable(with = FermentableUsageCode::class) val usage: FermentableUsage = FermentableUsage.default,
    @Serializable(with = BlankAsNullInt::class) val boilTimeMin: Int? = null,
    @Serializable(with = BlankAsNullDouble::class) val colourEbc: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val extractPercent: Double? = null,
) : FormRow

@Serializable
data class HopRow(
    override val name: String = "",
    @Serializable(with = BlankAsNullDouble::class) val amountG: Double? = null,
    @Serializable(with = HopUsageCode::class) val usage: HopUsage = HopUsage.default,
    @Serializable(with = BlankAsNullInt::class) val boilTimeMin: Int? = null,
    @Serializable(with = BlankAsNullDouble::class) val alphaAcid: Double? = null,
) : FormRow

@Serializable
data class YeastRow(
    override val name: String = "",
    @Serializable(with = YeastUsageCode::class) val usage: YeastUsage = YeastUsage.default,
    @Serializable(with = BlankAsNullDouble::class) val attenuation: Double? = null,
) : FormRow

@Serializable
data class ExtraRow(
    override val name: String = "",
    @Serializable(with = BlankAsNullDouble::class) val amount: Double? = null,
    @Serializable(with = AmountUnitCode::class) val unit: AmountUnit = AmountUnit.default,
    @Serializable(with = ExtraUsageCode::class) val usage: ExtraUsage = ExtraUsage.default,
    @Serializable(with = BlankAsNullInt::class) val boilTimeMin: Int? = null,
) : FormRow

fun RecipeForm.toNewRecipe(prefs: BrewerPreferences = BrewerPreferences()): NewRecipe {
    val boilTime = boilTimeMin ?: prefs.boilTimeMin
    return NewRecipe(
        name = name.trim(),
        style = style.trim().ifBlank { null },
        description = description.trim().ifBlank { null },
        preBoilVolumeL = preBoilVolumeL,
        postBoilVolumeL = postBoilVolumeL ?: prefs.postBoilVolumeL,
        fermenterVolumeL = fermenterVolumeL ?: prefs.fermenterVolumeL,
        efficiency = efficiency?.let(::percentToFraction) ?: prefs.efficiency,
        boilTimeMin = boilTime,
        mashSteps = mashSteps.mapNotNull { row ->
            MashStep(tempC = row.tempC ?: return@mapNotNull null, timeMin = row.timeMin ?: return@mapNotNull null)
        },
        fermentables = fermentables.filled().map {
            RecipeFermentable(
                name = it.name.trim(),
                amountKg = it.amountKg ?: 0.0,
                type = it.type,
                colourEbc = it.colourEbc,
                extractPercent = it.extractPercent,
                usage = it.usage,
                boilTimeMin = it.usage.kettleTime(it.boilTimeMin, boilTime),
            )
        },
        hops = hops.filled().map {
            RecipeHop(
                name = it.name.trim(),
                amountG = it.amountG ?: 0.0,
                alphaAcid = it.alphaAcid ?: 0.0,
                usage = it.usage,
                boilTimeMin = it.usage.kettleTime(it.boilTimeMin, boilTime),
            )
        },
        yeasts = yeasts.filled().map {
            RecipeYeast(
                name = it.name.trim(),
                attenuation = it.attenuation?.let(::percentToFraction) ?: DEFAULT_ATTENUATION,
                usage = it.usage,
            )
        },
        extras = extras.filled().map {
            RecipeExtra(
                name = it.name.trim(),
                amount = it.amount ?: 0.0,
                unit = it.unit,
                usage = it.usage,
                boilTimeMin = it.usage.kettleTime(it.boilTimeMin, boilTime),
            )
        },
    )
}

private fun <T : FormRow> List<T>.filled(): List<T> = filter { it.name.isNotBlank() }

internal fun BrewStage.kettleTime(minutes: Int?, boilTimeMin: Int): Int? = when {
    !takesTime || minutes == null -> null
    inBoil -> minutes.coerceAtMost(boilTimeMin)
    else -> minutes
}

internal fun percentToFraction(percent: Double) = if (percent <= 1.0) percent else percent / 100.0

internal fun Double.asPercent() = this * 100.0
