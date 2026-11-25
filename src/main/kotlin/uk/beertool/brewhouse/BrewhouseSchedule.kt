package uk.beertool.brewhouse

import uk.beertool.common.roundTo
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.mashGrainKg
import uk.beertool.recipe.mashWater
import uk.beertool.user.BrewerPreferences

private val STEP_TEMP_C = 20.0..80.0

// Rounded as the Brew Day mash-water guide displays them, so the controller heats exactly what the page tells the brewer.
fun brewhouseSchedule(batchId: Long, recipe: Recipe, prefs: BrewerPreferences): Schedule {
    if (recipe.mashSteps.isEmpty()) throw BrewhouseProblem(422, "The recipe has no mash steps, so there is nothing to run.")
    recipe.mashSteps.firstOrNull { it.tempC !in STEP_TEMP_C || it.timeMin <= 0 }?.let {
        throw BrewhouseProblem(422, "A mash step must be between 20 and 80 °C and last at least a minute.")
    }
    val water = recipe.mashWater(prefs.mashThicknessLPerKg, prefs.grainAbsorptionLPerKg)
    val preBoil = recipe.preBoilVolumeL
    if (water == null || preBoil == null) {
        throw BrewhouseProblem(422, "The recipe needs mash grain and a pre-boil volume to work out the strike water.")
    }
    return Schedule(
        batchId = batchId,
        recipeName = recipe.name,
        waterL = water.strikeVolumeL.roundTo(1),
        grainKg = recipe.mashGrainKg().roundTo(3),
        strikeC = water.strikeTempC.roundTo(1),
        steps = recipe.mashSteps.map { ScheduleStep(it.tempC, it.timeMin) },
        boil = recipe.boilTimeMin.takeIf { it > 0 }?.let { BoilPlan(it, preBoil) },
    )
}

