package uk.beertool.recipe

import uk.beertool.brewing.Calculators
import uk.beertool.brewing.FermentableAddition
import uk.beertool.brewing.HopAddition
import uk.beertool.brewing.MashRest

data class RecipeStats(
    val og: Double,
    val fg: Double,
    val abv: Double,
    val ibu: Double,
    val colourEbc: Double,
)

fun Recipe.stats(): RecipeStats =
    estimate(fermentables, hops, yeasts, mashSteps, preBoilVolumeL, postBoilVolumeL, fermenterVolumeL, efficiency)

fun NewRecipe.estimatedStats(): RecipeStats =
    estimate(fermentables, hops, yeasts, mashSteps, preBoilVolumeL, postBoilVolumeL, fermenterVolumeL, efficiency)

fun Recipe.lateAdditionAbv(fermenterVolumeL: Double = this.fermenterVolumeL): Double =
    Calculators.lateAdditionAbv(fermentables.map { it.toCalcInput() }, fermenterVolumeL)

fun Recipe.measuredEfficiency(measuredOg: Double, postBoilVolumeL: Double = this.postBoilVolumeL): Double? =
    Calculators.impliedEfficiency(fermentables.map { it.toCalcInput() }, postBoilVolumeL, measuredOg)

fun Recipe.mashGrainKg(): Double = fermentables.filter { it.type.isMashed }.sumOf { it.amountKg }

fun Recipe.unmaltedFraction(): Double = fermentables.unmaltedFraction()

fun NewRecipe.unmaltedFraction(): Double = fermentables.unmaltedFraction()

private fun List<RecipeFermentable>.unmaltedFraction(): Double {
    val grist = filter { it.type.isMashed }
    val total = grist.sumOf { it.amountKg }
    if (total <= 0.0) return 0.0
    return grist.filterNot { it.type.hasEnzymes }.sumOf { it.amountKg } / total
}

private fun estimate(
    fermentables: List<RecipeFermentable>,
    hops: List<RecipeHop>,
    yeasts: List<RecipeYeast>,
    mashSteps: List<MashStep>,
    preBoilVolumeL: Double?,
    postBoilVolumeL: Double,
    fermenterVolumeL: Double,
    efficiency: Double,
): RecipeStats {
    val bill = fermentables.map { it.toCalcInput() }
    val og = Calculators.estimateOg(bill, postBoilVolumeL, efficiency)

    val yeastAttenuation = yeasts.filter { it.usage.attenuates }.maxOfOrNull { it.attenuation } ?: DEFAULT_ATTENUATION
    val mashShift = Calculators.mashAttenuationShift(mashSteps.map { MashRest(it.tempC, it.timeMin) }) ?: 0.0
    val attenuation = (yeastAttenuation + mashShift).coerceIn(MIN_ATTENUATION, MAX_ATTENUATION)

    val fg = Calculators.estimateFg(bill, postBoilVolumeL, efficiency, attenuation)
    val bitteringHops = hops.filter { it.usage.bitters && it.boilTimeMin != null }.map { it.toCalcInput() }
    return RecipeStats(
        og = og,
        fg = fg,
        abv = Calculators.abv(og, fg) + Calculators.lateAdditionAbv(bill, fermenterVolumeL),
        ibu = Calculators.ibu(bitteringHops, postBoilVolumeL, Calculators.averageBoilGravity(og, postBoilVolumeL, preBoilVolumeL)),
        colourEbc = Calculators.colourEbc(bill, postBoilVolumeL),
    )
}

private fun RecipeFermentable.toCalcInput() = FermentableAddition(
    extractPercent = extractPercent ?: type.guessExtractPercent(),
    colourEbc = colourEbc ?: type.guessColourEbc(),
    massKg = amountKg,
    mashed = type.isMashed,
    inWort = usage.isInWort,
    fullyFermentable = type.isSimpleSugar,
)

private fun RecipeHop.toCalcInput() = HopAddition(
    alphaAcidPercent = alphaAcid,
    massGrams = amountG,
    boilTimeMinutes = boilTimeMin ?: 0,
)

private fun FermentableType.guessExtractPercent() = when (this) {
    FermentableType.MALT -> 80.0
    FermentableType.EXTRACT -> 95.0
    FermentableType.SUGAR -> 100.0
    FermentableType.UNMALTED -> 70.0
    FermentableType.FRUIT -> 10.0
}

private fun FermentableType.guessColourEbc() = when (this) {
    FermentableType.MALT -> 8.0
    FermentableType.EXTRACT -> 15.0
    FermentableType.SUGAR -> 0.0

    FermentableType.UNMALTED -> 4.0
    FermentableType.FRUIT -> 0.0
}
