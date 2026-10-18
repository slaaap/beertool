package uk.beertool.brewing

import kotlin.math.exp
import kotlin.math.pow

data class HopAddition(
    val alphaAcidPercent: Double,
    val massGrams: Double,

    // For a whirlpool hop, the stand at whirlpool temperature before chilling starts.
    val boilTimeMinutes: Int,

    val whirlpool: Whirlpool? = null,
)

data class Whirlpool(
    val tempC: Double,
    val coolingMinutes: Int,
)

data class FermentableAddition(
    val extractPercent: Double,
    val colourEbc: Double,
    val massKg: Double,

    val mashed: Boolean = true,

    val inWort: Boolean = true,

    val fullyFermentable: Boolean = false,
)

data class MashRest(
    val tempC: Double,
    val minutes: Int,
)

data class MashVolumes(
    val strikeL: Double,
    val spargeL: Double,
)

data class MashWater(
    val strikeVolumeL: Double,
    val strikeTempC: Double,
    val spargeVolumeL: Double,
)

object Calculators {
    private const val KG_TO_LB = 2.2046226218
    private const val L_TO_GAL = 0.2641720524
    private const val EBC_PER_LOVIBOND = 1.97
    private const val EBC_PER_SRM = 1.97

    // 1 kg of sucrose in 1 L raises gravity by about 384 points (0.384 SG).
    private const val SUCROSE_POINTS_PER_KG_PER_L = 384.0

    // The standard homebrew approximation, ABV = (OG - FG) x 131.25.
    private const val ABV_PER_GRAVITY_POINT = 131.25

    // Malowicki & Shellhammer's Arrhenius fit for hop isomerisation: activation energy over the gas constant, in kelvin.
    private const val ISOMERISATION_ACTIVATION_K = 11858.0
    private const val BOILING_K = 373.15
    private const val KELVIN_OFFSET = 273.15

    private const val PITCHING_TEMP_C = 20.0

    // The classic imperial strike rule's 0.2 per qt/lb, converted to L/kg.
    const val GRAIN_WATER_HEAT_RATIO = 0.41

    fun strikeTempCoefficient(thicknessLPerKg: Double) = GRAIN_WATER_HEAT_RATIO / thicknessLPerKg

    // Heat balance between strike water and grain: the water starts above the rest and the grain pulls it down.
    fun strikeTempC(thicknessLPerKg: Double, targetRestC: Double, grainTempC: Double) =
        targetRestC + strikeTempCoefficient(thicknessLPerKg) * (targetRestC - grainTempC)

    fun mashWater(
        grainKg: Double,
        firstRestTempC: Double,
        grainTempC: Double,
        preBoilVolumeL: Double,
        thicknessLPerKg: Double,
        absorptionLPerKg: Double,
    ): MashWater {
        val (strike, sparge) = mashVolumes(grainKg, preBoilVolumeL, thicknessLPerKg, absorptionLPerKg)
        return MashWater(strike, strikeTempC(thicknessLPerKg, firstRestTempC, grainTempC), sparge)
    }

    fun mashVolumes(grainKg: Double, preBoilVolumeL: Double, thicknessLPerKg: Double, absorptionLPerKg: Double): MashVolumes {
        val strike = grainKg * thicknessLPerKg
        val absorbed = grainKg * absorptionLPerKg
        // A full-volume mash already holds the whole pre-boil volume, so there is nothing to sparge.
        return MashVolumes(strike, (preBoilVolumeL + absorbed - strike).coerceAtLeast(0.0))
    }

    fun abv(og: Double, fg: Double) = (og - fg) * ABV_PER_GRAVITY_POINT

    // Sugar added in the fermenter ferments out fully and never shows in the OG or FG the brewer reads.
    fun lateAdditionAbv(fermentables: List<FermentableAddition>, volumeL: Double): Double {
        val points = fermentables.filterNot { it.inWort }.gravityPoints(volumeL, efficiency = 1.0)
        return points / 1000.0 * ABV_PER_GRAVITY_POINT
    }

    fun estimateOg(fermentables: List<FermentableAddition>, volumeL: Double, efficiency: Double) =
        1.0 + fermentables.filter { it.inWort }.gravityPoints(volumeL, efficiency) / 1000.0

    // Simple sugars ferment out completely, so only malt extract leaves residual gravity.
    fun estimateFg(
        fermentables: List<FermentableAddition>,
        volumeL: Double,
        efficiency: Double,
        attenuation: Double,
    ): Double {
        val maltPoints = fermentables.filter { it.inWort && !it.fullyFermentable }
            .gravityPoints(volumeL, efficiency)
        return 1.0 + maltPoints * (1.0 - attenuation) / 1000.0
    }

    // The inverse of [gravityPoints]: unmashed sugars always yield fully, so only mashed grain carries the efficiency.
    fun impliedEfficiency(
        fermentables: List<FermentableAddition>,
        volumeL: Double,
        measuredOg: Double,
    ): Double? {
        require(volumeL > 0) { "volume must be > 0" }
        val inWort = fermentables.filter { it.inWort }
        val mashedExtractKg = inWort.filter { it.mashed }.sumOf { (it.extractPercent / 100.0) * it.massKg }
        if (mashedExtractKg <= 0) return null

        val freeExtractKg = inWort.filterNot { it.mashed }.sumOf { (it.extractPercent / 100.0) * it.massKg }
        val measuredExtractKg = (measuredOg - 1.0) * 1000.0 * volumeL / SUCROSE_POINTS_PER_KG_PER_L
        return (measuredExtractKg - freeExtractKg) / mashedExtractKg
    }

    // Hops see thinner wort early in the boil, so utilisation uses the mean of pre- and post-boil gravity.
    fun averageBoilGravity(og: Double, postBoilVolumeL: Double, preBoilVolumeL: Double?): Double {
        require(postBoilVolumeL > 0) { "volume must be > 0" }
        if (preBoilVolumeL == null || preBoilVolumeL <= postBoilVolumeL) return og
        val ogPoints = (og - 1.0) * 1000.0
        val preBoilPoints = ogPoints * postBoilVolumeL / preBoilVolumeL
        return 1.0 + (ogPoints + preBoilPoints) / 2.0 / 1000.0
    }

    // Tinseth's model; a whirlpool hop counts as the boil time that would isomerise as much.
    fun ibu(hops: List<HopAddition>, volumeL: Double, boilGravity: Double): Double {
        require(volumeL > 0) { "volume must be > 0" }
        val bigness = 1.65 * 0.000125.pow(boilGravity - 1.0)
        return hops.sumOf { hop ->
            val minutes = hop.whirlpool?.let { whirlpoolBoilMinutes(hop.boilTimeMinutes, it) }
                ?: hop.boilTimeMinutes.toDouble()
            val boilTimeFactor = (1 - exp(-0.04 * minutes)) / 4.15
            val utilisation = bigness * boilTimeFactor
            val mgPerLitre = (hop.alphaAcidPercent / 100.0) * hop.massGrams * 1000.0 / volumeL
            mgPerLitre * utilisation
        }
    }

    // Relative to the boil: about 0.42 at 90 °C, 0.17 at 80 °C and 0.06 at 70 °C.
    fun isomerisationRate(tempC: Double) =
        exp(-ISOMERISATION_ACTIVATION_K * (1.0 / (tempC + KELVIN_OFFSET) - 1.0 / BOILING_K))

    // Tinseth's time curve is first-order, so slower minutes fold in as equivalent boil minutes; the chill is taken as linear.
    fun whirlpoolBoilMinutes(standMinutes: Int, whirlpool: Whirlpool): Double {
        val stand = standMinutes * isomerisationRate(whirlpool.tempC)
        val drop = whirlpool.tempC - PITCHING_TEMP_C
        val chill = (0 until whirlpool.coolingMinutes).sumOf { minute ->
            isomerisationRate(whirlpool.tempC - drop * (minute + 0.5) / whirlpool.coolingMinutes)
        }
        return stand + chill
    }

    // Morey's equation, which works in imperial units and SRM.
    fun colourEbc(fermentables: List<FermentableAddition>, volumeL: Double): Double {
        require(volumeL > 0) { "volume must be > 0" }
        val gallons = volumeL * L_TO_GAL

        val mcu = fermentables.sumOf { (it.colourEbc / EBC_PER_LOVIBOND) * it.massKg * KG_TO_LB } / gallons
        val srm = 1.4922 * mcu.pow(0.6859)
        return srm * EBC_PER_SRM
    }

    // Not a textbook model: beta-amylase's share of the enzyme work, as an attenuation shift from a 66.5 °C / 60 min mash.
    fun mashAttenuationShift(rests: List<MashRest>): Double? {
        val index = fermentabilityIndex(rests) ?: return null
        val reference = fermentabilityIndex(listOf(MashRest(REFERENCE_MASH_C, REFERENCE_MASH_MIN)))!!
        return (index - reference) * ATTENUATION_SPAN
    }

    fun fermentabilityIndex(rests: List<MashRest>): Double? {
        if (rests.isEmpty()) return null
        val beta = rests.enzymeWork(BETA)
        val alpha = rests.enzymeWork(ALPHA)
        if (beta + alpha < MIN_CONVERSION_WORK) return null
        return beta / (beta + alpha)
    }

    // Each rest weighs its activity by the enzyme still alive, averaged over the rest as it denatures.
    private fun List<MashRest>.enzymeWork(enzyme: Enzyme): Double {
        var surviving = 1.0
        var work = 0.0
        forEach { rest ->
            val after = surviving * enzyme.survives(rest)
            work += enzyme.activityAt(rest.tempC) * (surviving + after) / 2.0 * rest.minutes
            surviving = after
        }
        return work
    }

    private fun Enzyme.activityAt(tempC: Double) = exp(-((tempC - optimumC) / widthC).pow(2))

    private fun Enzyme.survives(rest: MashRest): Double {
        if (rest.tempC <= denaturesFromC) return 1.0
        val halfLifeMin = BASE_HALF_LIFE_MIN * exp(-(rest.tempC - denaturesFromC) * decayPerC)
        return 0.5.pow(rest.minutes / halfLifeMin)
    }

    private class Enzyme(
        val optimumC: Double,
        val widthC: Double,
        val denaturesFromC: Double,
        val decayPerC: Double,
    )

    // Beta-amylase makes fermentable maltose, works cool and denatures early.
    private val BETA = Enzyme(optimumC = 62.0, widthC = 6.0, denaturesFromC = 63.0, decayPerC = 0.55)

    // Alpha-amylase makes larger, less fermentable sugars and survives hotter rests.
    private val ALPHA = Enzyme(optimumC = 72.0, widthC = 7.0, denaturesFromC = 73.0, decayPerC = 0.60)

    private const val BASE_HALF_LIFE_MIN = 1000.0

    private const val REFERENCE_MASH_C = 66.5
    private const val REFERENCE_MASH_MIN = 60

    // The attenuation swing between an all-alpha and an all-beta mash.
    private const val ATTENUATION_SPAN = 0.165

    private const val MIN_CONVERSION_WORK = 1.0

    // Mashed grain loses some extract in the tun; sugars and extracts dissolve fully.
    private fun List<FermentableAddition>.gravityPoints(volumeL: Double, efficiency: Double): Double {
        require(volumeL > 0) { "volume must be > 0" }
        val extractKg = sumOf { (it.extractPercent / 100.0) * it.massKg * (if (it.mashed) efficiency else 1.0) }
        return extractKg * SUCROSE_POINTS_PER_KG_PER_L / volumeL
    }
}
