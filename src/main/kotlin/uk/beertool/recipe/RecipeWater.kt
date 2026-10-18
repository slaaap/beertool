package uk.beertool.recipe

import kotlinx.serialization.Serializable
import uk.beertool.brewing.Acid
import uk.beertool.brewing.Calculators
import uk.beertool.brewing.FermentableAddition
import uk.beertool.brewing.Salt
import uk.beertool.brewing.SaltAddition
import uk.beertool.brewing.WaterChemistry
import uk.beertool.brewing.WaterProfile
import uk.beertool.common.formatAmount
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.WaterSource

const val TARGET_MASH_PH = 5.4
const val MIN_MASH_PH = 5.2
const val MAX_MASH_PH = 5.6

@Serializable
data class RecipeWater(
    val source: String,
    val blendSource: String? = null,
    val blendPercent: Double = 0.0,
    val gypsumG: Double = 0.0,
    val calciumChlorideG: Double = 0.0,
    val epsomG: Double = 0.0,
    val tableSaltG: Double = 0.0,
    val bakingSodaG: Double = 0.0,
    val acid: Acid = Acid.LACTIC_80,
    val acidMl: Double = 0.0,
    val campdenTablets: Double = 0.0,
) {
    val salts: List<SaltAddition>
        get() = WaterAddition.entries.mapNotNull { a -> a.salt?.let { SaltAddition(it, a.amountIn(this)) } }.filter { it.grams > 0.0 }

    val additions get() = WaterAddition.entries.filter { it.amountIn(this) > 0.0 }

    val blends get() = blendSource != null && blendPercent > 0.0
}

enum class WaterAddition(
    val field: String,
    private val label: String,
    val unit: String,
    val salt: Salt?,
    val mashOnly: Boolean,
    val amountIn: (RecipeWater) -> Double,
) {
    GYPSUM("gypsumG", "Gypsum", "g", Salt.GYPSUM, false, RecipeWater::gypsumG),
    CALCIUM_CHLORIDE("calciumChlorideG", "Calcium chloride", "g", Salt.CALCIUM_CHLORIDE, false, RecipeWater::calciumChlorideG),
    EPSOM("epsomG", "Epsom salt", "g", Salt.EPSOM, false, RecipeWater::epsomG),
    TABLE_SALT("tableSaltG", "Table salt", "g", Salt.TABLE_SALT, false, RecipeWater::tableSaltG),
    BAKING_SODA("bakingSodaG", "Baking soda", "g", Salt.BAKING_SODA, false, RecipeWater::bakingSodaG),

    // Acid is there to bring the mash pH down, so all of it goes in the mash.
    ACID("acidMl", "Acid", "ml", null, true, RecipeWater::acidMl),

    // Campden tablets clear chlorine and chloramine from all the water before it is heated; the minerals barely move.
    CAMPDEN("campdenTablets", "Campden", "tablets", null, false, RecipeWater::campdenTablets);

    fun labelIn(water: RecipeWater?) = if (this == ACID && water != null) water.acid.label else label
}

data class WaterDose(val addition: WaterAddition, val strike: Double, val sparge: Double)

// Salts go in at the same strength across all the water, so each share follows its volume.
fun WaterReport.doses(water: RecipeWater): List<WaterDose> {
    val strikeShare = strikeL?.let { it / totalL } ?: 1.0
    return water.additions.map { a ->
        val total = a.amountIn(water)
        val strike = if (a.mashOnly) total else total * strikeShare
        WaterDose(a, strike, total - strike)
    }
}

// What a brew was made with: the sources are copied so later edits in Settings leave the brew log alone.
@Serializable
data class WaterSnapshot(
    val water: RecipeWater,
    val sources: List<WaterSource>,
)

fun BrewerPreferences.snapshot(water: RecipeWater) =
    WaterSnapshot(water, waterSources.filter { it.name == water.source || it.name == water.blendSource })

fun BrewerPreferences.defaultRecipeWater() = waterSources.firstOrNull()?.let { RecipeWater(source = it.name) }

fun RecipeWater.describe(): String {
    if (!blends) return source
    return "$source ${formatAmount(100.0 - blendPercent)}% + $blendSource ${formatAmount(blendPercent)}%"
}

enum class FlavourBalance(val label: String) { BITTER("Bitter"), BALANCED("Balanced"), MALTY("Malty") }

data class WaterReport(
    val totalL: Double,
    val strikeL: Double?,
    val spargeL: Double?,
    val profile: WaterProfile,
    val mashPh: Double?,
    val acid: Acid,
    val acidForTargetMl: Double?,
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
            missingSources.forEach { add("\"$it\" is no longer in your water sources, so its minerals are left out.") }
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
    water?.let { waterReport(it, prefs.waterSources, fermentables, preBoilVolumeL, postBoilVolumeL, prefs) }

fun NewRecipe.waterReport(prefs: BrewerPreferences): WaterReport? =
    water?.let { waterReport(it, prefs.waterSources, fermentables, preBoilVolumeL, postBoilVolumeL, prefs) }

fun Recipe.waterReport(snapshot: WaterSnapshot, prefs: BrewerPreferences): WaterReport =
    waterReport(snapshot.water, snapshot.sources, fermentables, preBoilVolumeL, postBoilVolumeL, prefs)

private fun waterReport(
    water: RecipeWater,
    sources: List<WaterSource>,
    fermentables: List<RecipeFermentable>,
    preBoilVolumeL: Double?,
    postBoilVolumeL: Double,
    prefs: BrewerPreferences,
): WaterReport {
    val missing = mutableListOf<String>()
    fun profileOf(name: String): WaterProfile =
        sources.find { it.name == name }?.profile ?: WaterProfile.DISTILLED.also { missing += name }

    val base = profileOf(water.source)
    val mixed = water.blendSource?.takeIf { water.blends }
        ?.let { WaterChemistry.blend(base, profileOf(it), water.blendPercent / 100.0) } ?: base

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
        mashPh = WaterChemistry.mashPh(grist, profile, strikeL, water.acidMl * water.acid.meqPerMl),
        acid = water.acid,
        acidForTargetMl = WaterChemistry.acidForPh(TARGET_MASH_PH, water.acid, grist, profile, strikeL),
        missingSources = missing.distinct(),
    )
}
