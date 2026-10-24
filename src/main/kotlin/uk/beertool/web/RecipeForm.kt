package uk.beertool.web

import kotlinx.serialization.Serializable
import uk.beertool.brewing.Acid
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
import uk.beertool.recipe.RecipeWater
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
    val water: WaterForm = WaterForm(),
)

@Serializable
data class WaterForm(
    val source: String = "",
    val blendSource: String = "",
    @Serializable(with = BlankAsNullDouble::class) val blendPercent: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val gypsumG: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val calciumChlorideG: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val epsomG: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val tableSaltG: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val bakingSodaG: Double? = null,
    val acid: String = "",
    @Serializable(with = BlankAsNullDouble::class) val acidMl: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val campdenTablets: Double? = null,
)

fun WaterForm.toRecipeWater(): RecipeWater? {
    val name = source.trim().ifBlank { return null }
    val partner = blendSource.trim().ifBlank { null }
    val percent = if (partner == null) 0.0 else (blendPercent ?: 0.0).coerceIn(0.0, 100.0)
    return RecipeWater(
        source = name,
        blendSource = partner?.takeIf { percent > 0.0 },
        blendPercent = percent,
        gypsumG = gypsumG.amount("Gypsum"),
        calciumChlorideG = calciumChlorideG.amount("Calcium chloride"),
        epsomG = epsomG.amount("Epsom salt"),
        tableSaltG = tableSaltG.amount("Table salt"),
        bakingSodaG = bakingSodaG.amount("Baking soda"),
        acid = Acid.fromCodeOrNull(acid) ?: Acid.LACTIC_80,
        acidMl = acidMl.amount("Acid"),
        campdenTablets = campdenTablets.amount("Campden tablets"),
    )
}

private fun Double?.amount(label: String) = within(label, WATER_ADDITION) ?: 0.0

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
    @Serializable(with = BlankAsNullInt::class) val dryHopDays: Int? = null,
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
    val boilTime = boilTimeMin.within("Boil time", MINUTES) ?: prefs.boilTimeMin
    return NewRecipe(
        name = name.trim(),
        style = style.trim().ifBlank { null },
        description = description.trim().ifBlank { null },
        preBoilVolumeL = preBoilVolumeL.within("Pre-boil volume", VOLUME_L),
        postBoilVolumeL = postBoilVolumeL.within("Post-boil volume", VOLUME_L) ?: prefs.postBoilVolumeL,
        fermenterVolumeL = fermenterVolumeL.within("Fermenter volume", VOLUME_L) ?: prefs.fermenterVolumeL,
        efficiency = efficiency.within("Efficiency", PERCENT)?.let(::percentToFraction) ?: prefs.efficiency,
        boilTimeMin = boilTime,
        mashSteps = mashSteps.toMashSteps(),
        fermentables = fermentables.filled().map {
            RecipeFermentable(
                name = it.name.trim(),
                amountKg = it.amountKg.within("${it.name} amount", GRAIN_KG) ?: 0.0,
                type = it.type,
                colourEbc = it.colourEbc.within("${it.name} colour", COLOUR_EBC),
                extractPercent = it.extractPercent.within("${it.name} extract", PERCENT),
                usage = it.usage,
                boilTimeMin = it.usage.kettleTime(it.boilTimeMin.within("${it.name} time", MINUTES), boilTime),
            )
        },
        hops = hops.filled().map {
            RecipeHop(
                name = it.name.trim(),
                amountG = it.amountG.within("${it.name} amount", HOP_G) ?: 0.0,
                alphaAcid = it.alphaAcid.within("${it.name} alpha acid", ALPHA_ACID) ?: 0.0,
                usage = it.usage,
                boilTimeMin = it.usage.kettleTime(it.boilTimeMin.within("${it.name} time", MINUTES), boilTime),
                dryHopDays = it.dryHopDays.within("${it.name} days", DRY_HOP_DAYS)?.takeIf { _ -> it.usage == HopUsage.DRY_HOP },
            )
        },
        yeasts = yeasts.filled().map {
            RecipeYeast(
                name = it.name.trim(),
                attenuation = it.attenuation.within("${it.name} attenuation", PERCENT)?.let(::percentToFraction) ?: DEFAULT_ATTENUATION,
                usage = it.usage,
            )
        },
        extras = extras.filled().map {
            RecipeExtra(
                name = it.name.trim(),
                amount = it.amount.within("${it.name} amount", EXTRA_AMOUNT) ?: 0.0,
                unit = it.unit,
                usage = it.usage,
                boilTimeMin = it.usage.kettleTime(it.boilTimeMin.within("${it.name} time", MINUTES), boilTime),
            )
        },
        water = water.toRecipeWater(),
    )
}

internal fun List<MashStepRow>.toMashSteps() = mapNotNull { row ->
    MashStep(
        tempC = row.tempC.within("Mash temperature", TEMP_C) ?: return@mapNotNull null,
        timeMin = row.timeMin.within("Mash time", MINUTES) ?: return@mapNotNull null,
    )
}

private fun <T : FormRow> List<T>.filled(): List<T> = filter { it.name.isNotBlank() }

internal fun BrewStage.kettleTime(minutes: Int?, boilTimeMin: Int): Int? = when {
    !takesTime || minutes == null -> null
    inBoil -> minutes.coerceAtMost(boilTimeMin)
    else -> minutes
}

internal fun percentToFraction(percent: Double) = percent / 100.0
