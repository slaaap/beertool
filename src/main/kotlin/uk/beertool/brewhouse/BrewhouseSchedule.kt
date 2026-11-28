package uk.beertool.brewhouse

import uk.beertool.common.roundTo
import uk.beertool.recipe.DEFAULT_GRAIN_TEMP_C
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.mashGrainKg
import uk.beertool.recipe.mashWater
import uk.beertool.recipe.whirlpoolStandMin
import uk.beertool.user.BrewerPreferences

private val STEP_TEMP_C = 20.0..80.0
private val GRAIN_TEMP_C = 0.0..45.0
private val THICKNESS_L_PER_KG = 1.0..10.0
private val WHIRLPOOL_TEMP_C = 20.0..100.0

// What the brewer adjusted in the Brew Day mash-water guide; null keeps the default or their settings.
data class StrikeInputs(val grainTempC: Double? = null, val mashThicknessLPerKg: Double? = null) {
    init {
        if (grainTempC != null && grainTempC !in GRAIN_TEMP_C) throw BrewhouseProblem(400, "The grain temperature must be between 0 and 45 °C.")
        if (mashThicknessLPerKg != null && mashThicknessLPerKg !in THICKNESS_L_PER_KG) {
            throw BrewhouseProblem(400, "The mash thickness must be between 1 and 10 L/kg.")
        }
    }
}

// Rounded as the Brew Day mash-water guide displays them, so the controller heats exactly what the page tells the brewer.
fun brewhouseSchedule(batchId: Long, recipe: Recipe, prefs: BrewerPreferences, strike: StrikeInputs = StrikeInputs()): Schedule {
    if (recipe.mashSteps.isEmpty()) throw BrewhouseProblem(422, "The recipe has no mash steps, so there is nothing to run.")
    recipe.mashSteps.firstOrNull { it.tempC !in STEP_TEMP_C || it.timeMin <= 0 }?.let {
        throw BrewhouseProblem(422, "A mash step must be between 20 and 80 °C and last at least a minute.")
    }
    val water = recipe.mashWater(
        strike.mashThicknessLPerKg ?: prefs.mashThicknessLPerKg,
        prefs.grainAbsorptionLPerKg,
        strike.grainTempC ?: DEFAULT_GRAIN_TEMP_C,
    )
    val preBoil = recipe.preBoilVolumeL
    if (water == null || preBoil == null) {
        throw BrewhouseProblem(422, "The recipe needs mash grain and a pre-boil volume to work out the strike water.")
    }
    val boil = recipe.boilTimeMin.takeIf { it > 0 }?.let { BoilPlan(it, preBoil) }
    return Schedule(
        batchId = batchId,
        recipeName = recipe.name,
        waterL = water.strikeVolumeL.roundTo(1),
        grainKg = recipe.mashGrainKg().roundTo(3),
        strikeC = water.strikeTempC.roundTo(1),
        steps = recipe.mashSteps.map { ScheduleStep(it.tempC, it.timeMin) },
        boil = boil,
        // A stand of at least a minute, so the brewhouse always passes through whirlpool and the brewer is told to add.
        whirlpool = if (boil == null) null else recipe.whirlpoolStandMin()?.let {
            WhirlpoolPlan(prefs.whirlpoolTempC.coerceIn(WHIRLPOOL_TEMP_C), it.coerceAtLeast(1))
        },
    )
}

