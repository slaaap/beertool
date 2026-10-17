package uk.beertool.recipe

import kotlinx.serialization.Serializable
import uk.beertool.brewing.Calculators
import uk.beertool.brewing.FermentableAddition
import uk.beertool.brewing.Salt
import uk.beertool.brewing.SaltAddition
import uk.beertool.brewing.WaterChemistry
import uk.beertool.brewing.WaterProfile
import uk.beertool.user.BrewerPreferences

const val TARGET_MASH_PH = 5.4
const val MIN_MASH_PH = 5.2
const val MAX_MASH_PH = 5.6

// A null source means distilled or RO water.
@Serializable
data class RecipeWater(
    val source: String? = null,
    val blendSource: String? = null,
    val blendPercent: Double = 0.0,
    val gypsumG: Double = 0.0,
    val calciumChlorideG: Double = 0.0,
    val epsomG: Double = 0.0,
    val tableSaltG: Double = 0.0,
    val bakingSodaG: Double = 0.0,
    val lacticMl: Double = 0.0,
) {
    val salts: List<SaltAddition>
        get() = listOf(
            SaltAddition(Salt.GYPSUM, gypsumG),
            SaltAddition(Salt.CALCIUM_CHLORIDE, calciumChlorideG),
            SaltAddition(Salt.EPSOM, epsomG),
            SaltAddition(Salt.TABLE_SALT, tableSaltG),
            SaltAddition(Salt.BAKING_SODA, bakingSodaG),
        ).filter { it.grams > 0.0 }

    val blends get() = blendPercent > 0.0
}

const val DISTILLED_WATER = "Distilled / RO"

fun BrewerPreferences.defaultRecipeWater() = waterSources.firstOrNull()?.let { RecipeWater(source = it.name) }

fun RecipeWater.describe(): String {
    val base = source ?: DISTILLED_WATER
    if (!blends) return base
    return "$base ${fmtPercent(100.0 - blendPercent)}% + ${blendSource ?: DISTILLED_WATER} ${fmtPercent(blendPercent)}%"
}

private fun fmtPercent(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

enum class FlavourBalance(val label: String) { BITTER("Bitter"), BALANCED("Balanced"), MALTY("Malty") }

data class WaterReport(
    val totalL: Double,
    val strikeL: Double?,
    val spargeL: Double?,
    val profile: WaterProfile,
    val mashPh: Double?,
    val lacticForTargetMl: Double?,
    val missingSources: List<String>,
) {
    val balance: FlavourBalance?
        get() {
            val ratio = profile.chlorideToSulphate ?: return if (profile.chloride > 0.0) FlavourBalance.MALTY else null
            return when {
                ratio < 0.77 -> FlavourBalance.BITTER
                ratio > 1.3 -> FlavourBalance.MALTY
                else -> FlavourBalance.BALANCED
            }
        }

    val warnings: List<String>
        get() = buildList {
            missingSources.forEach { add("\"$it\" is no longer in your water sources, so it counts as distilled water.") }
            if (profile.calcium < 50.0) add("Calcium is under 50 mg/L, which can slow the yeast and leave the beer hazy.")
            if (profile.magnesium > 30.0) add("Magnesium above 30 mg/L can taste sour and bitter.")
            if (profile.sodium > 150.0) add("Sodium above 150 mg/L can taste salty.")
            mashPh?.let { ph ->
                if (ph > MAX_MASH_PH) add("Mash pH is above $MAX_MASH_PH: expect a harsher, duller beer. Add acid.")
                if (ph < MIN_MASH_PH) add("Mash pH is below $MIN_MASH_PH: expect a thin, tart beer. Cut the acid or add baking soda.")
            }
        }
}

fun Recipe.waterReport(prefs: BrewerPreferences): WaterReport? =
    water?.let { waterReport(it, fermentables, preBoilVolumeL, postBoilVolumeL, prefs) }

fun NewRecipe.waterReport(prefs: BrewerPreferences): WaterReport? =
    water?.let { waterReport(it, fermentables, preBoilVolumeL, postBoilVolumeL, prefs) }

private fun waterReport(
    water: RecipeWater,
    fermentables: List<RecipeFermentable>,
    preBoilVolumeL: Double?,
    postBoilVolumeL: Double,
    prefs: BrewerPreferences,
): WaterReport {
    val missing = mutableListOf<String>()
    fun profileOf(name: String?): WaterProfile {
        if (name == null) return WaterProfile.DISTILLED
        return prefs.waterSources.find { it.name == name }?.profile ?: WaterProfile.DISTILLED.also { missing += name }
    }

    val base = profileOf(water.source)
    val mixed = if (water.blends) WaterChemistry.blend(base, profileOf(water.blendSource), water.blendPercent / 100.0) else base

    val grainKg = fermentables.filter { it.type.isMashed }.sumOf { it.amountKg }
    val preBoil = preBoilVolumeL ?: postBoilVolumeL
    val mash = if (grainKg > 0.0) Calculators.mashVolumes(grainKg, preBoil, prefs.mashThicknessLPerKg, prefs.grainAbsorptionLPerKg) else null
    val totalL = mash?.let { it.strikeL + it.spargeL } ?: preBoil
    val profile = WaterChemistry.withSalts(mixed, water.salts, totalL)

    val grist = fermentables.map { it.toCalcInput() }
    val strikeL = mash?.strikeL ?: 0.0
    return WaterReport(
        totalL = totalL,
        strikeL = mash?.strikeL,
        spargeL = mash?.spargeL,
        profile = profile,
        mashPh = WaterChemistry.mashPh(grist, profile, strikeL, water.lacticMl),
        lacticForTargetMl = WaterChemistry.lacticForPh(TARGET_MASH_PH, grist, profile, strikeL),
        missingSources = missing.distinct(),
    )
}
