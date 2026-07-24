package uk.beertool.brewing

import kotlin.math.exp
import kotlin.math.pow

data class HopAddition(
    val alphaAcidPercent: Double,
    val massGrams: Double,
    val boilTimeMinutes: Int,
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

    // Extract-to-gravity yield of pure sucrose: 1 kg dissolved in 1 L raises gravity by ~384 points
    // (1 point = 0.001 of specific gravity). A fermentable contributes this scaled by its extract %.
    private const val SUCROSE_POINTS_PER_KG_PER_L = 384.0

    // ABV ≈ (OG − FG) × 131.25 — the standard hobby approximation (points of gravity drop → % alcohol).
    private const val ABV_PER_GRAVITY_POINT = 131.25

    // Ratio of grain's specific heat to water's, in the metric strike-temp balance below. Chosen so the
    // L/kg form matches the classic imperial "0.2 / (qt·lb⁻¹)" rule (0.2 × 2.086 L·kg⁻¹ per qt·lb⁻¹ ≈ 0.41).
    const val GRAIN_WATER_HEAT_RATIO = 0.41

    fun strikeTempCoefficient(thicknessLPerKg: Double) = GRAIN_WATER_HEAT_RATIO / thicknessLPerKg

    // Strike water is poured onto room-temperature grain, so it must start ABOVE the target rest; the
    // grain then pulls it down. From the heat balance mass_grain·c_grain·ΔT_grain = mass_water·ΔT_water,
    // with the water:grain mass ratio = thickness (L/kg): Tstrike = Trest + (k/thickness)·(Trest − Tgrain).
    fun strikeTempC(thicknessLPerKg: Double, targetRestC: Double, grainTempC: Double) =
        targetRestC + strikeTempCoefficient(thicknessLPerKg) * (targetRestC - grainTempC)

    // The water plan for a single-infusion mash + sparge:
    //  - strike  = grain × thickness (the water that mashes in)
    //  - sparge  = pre-boil volume + what the grain absorbs − strike (what's left to rinse to pre-boil);
    //              clamped at 0 for a full-volume/BIAB mash that already holds everything.
    fun mashWater(
        grainKg: Double,
        firstRestTempC: Double,
        grainTempC: Double,
        preBoilVolumeL: Double,
        thicknessLPerKg: Double,
        absorptionLPerKg: Double,
    ): MashWater {
        val strike = grainKg * thicknessLPerKg
        val absorbed = grainKg * absorptionLPerKg
        val sparge = (preBoilVolumeL + absorbed - strike).coerceAtLeast(0.0)
        return MashWater(strike, strikeTempC(thicknessLPerKg, firstRestTempC, grainTempC), sparge)
    }

    fun abv(og: Double, fg: Double) = (og - fg) * ABV_PER_GRAVITY_POINT

    // Sugar/fruit added AFTER the wort (in the fermenter) ferments out fully and isn't in the OG/FG the
    // brewer reads, so its alcohol is counted on its own: full-efficiency gravity points → ABV.
    fun lateAdditionAbv(fermentables: List<FermentableAddition>, volumeL: Double): Double {
        val points = fermentables.filterNot { it.inWort }.gravityPoints(volumeL, efficiency = 1.0)
        return points / 1000.0 * ABV_PER_GRAVITY_POINT
    }

    // OG = 1 + points/1000, where points come from every in-wort fermentable at the given efficiency.
    fun estimateOg(fermentables: List<FermentableAddition>, volumeL: Double, efficiency: Double) =
        1.0 + fermentables.filter { it.inWort }.gravityPoints(volumeL, efficiency) / 1000.0

    // FG: only the non-fully-fermentable extract (malt) leaves residual gravity — a fraction (1 −
    // attenuation) of the malt points stays behind. Simple sugars ferment to nothing, so they're excluded.
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

    // Back out the mash efficiency a brew actually hit: convert the MEASURED OG to extract in the kettle,
    // subtract the extract from unmashed sugars (which arrive at 100% regardless), and divide by the
    // potential extract of the mashed grain. Inverse of [gravityPoints]. Null if there's nothing mashed.
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

    // Hop utilisation happens during the boil, when the wort is more concentrated than the final OG. This
    // approximates the average gravity the hops saw as the mean of pre-boil and post-boil gravity (the
    // wort concentrates as it boils down), so IBUs aren't over-counted. Falls back to OG if pre-boil ≤ post.
    fun averageBoilGravity(og: Double, postBoilVolumeL: Double, preBoilVolumeL: Double?): Double {
        require(postBoilVolumeL > 0) { "volume must be > 0" }
        if (preBoilVolumeL == null || preBoilVolumeL <= postBoilVolumeL) return og
        val ogPoints = (og - 1.0) * 1000.0
        // Same sugar in a larger pre-boil volume → proportionally lower gravity.
        val preBoilPoints = ogPoints * postBoilVolumeL / preBoilVolumeL
        return 1.0 + (ogPoints + preBoilPoints) / 2.0 / 1000.0
    }

    // Bitterness by Tinseth's model. Utilisation = bigness × boil-time factor:
    //  - bigness (gravity factor): denser wort extracts less; 1.65 × 0.000125^(SG−1).
    //  - boil-time factor: iso-alpha rises then plateaus with time; (1 − e^(−0.04·min)) / 4.15.
    // IBU (mg/L iso-alpha) = Σ over hops of (AA% × grams × 1000 / L) × utilisation.
    fun ibu(hops: List<HopAddition>, volumeL: Double, boilGravity: Double): Double {
        require(volumeL > 0) { "volume must be > 0" }
        val bigness = 1.65 * 0.000125.pow(boilGravity - 1.0)
        return hops.sumOf { hop ->
            val boilTimeFactor = (1 - exp(-0.04 * hop.boilTimeMinutes)) / 4.15
            val utilisation = bigness * boilTimeFactor
            val mgPerLitre = (hop.alphaAcidPercent / 100.0) * hop.massGrams * 1000.0 / volumeL
            mgPerLitre * utilisation
        }
    }

    // Colour by Morey's equation. MCU = Σ(grain colour in °Lovibond × weight in lb) / volume in gal;
    // SRM = 1.4922 × MCU^0.6859 (the curve that keeps dark beers from over-counting). Reported in EBC.
    fun colourEbc(fermentables: List<FermentableAddition>, volumeL: Double): Double {
        require(volumeL > 0) { "volume must be > 0" }
        val gallons = volumeL * L_TO_GAL

        val mcu = fermentables.sumOf { (it.colourEbc / EBC_PER_LOVIBOND) * it.massKg * KG_TO_LB } / gallons
        val srm = 1.4922 * mcu.pow(0.6859)
        return srm * EBC_PER_SRM
    }

    // A NOT-textbook heuristic: mash temperature shapes how fermentable the wort is (a cooler rest
    // favours beta-amylase → drier beer; a hotter rest favours alpha → sweeter). We model the two
    // enzymes' work over the schedule, take beta's share of the total, and express the result as a shift
    // in apparent attenuation relative to a reference 66.5 °C / 60 min mash. Feeds the FG estimate.
    fun mashAttenuationShift(rests: List<MashRest>): Double? {
        val index = fermentabilityIndex(rests) ?: return null
        val reference = fermentabilityIndex(listOf(MashRest(REFERENCE_MASH_C, REFERENCE_MASH_MIN)))!!
        return (index - reference) * ATTENUATION_SPAN
    }

    // Beta's fraction of the total enzyme work across the schedule (0 = all alpha/sweet, 1 = all
    // beta/dry). Null when the rests do too little conversion to mean anything.
    fun fermentabilityIndex(rests: List<MashRest>): Double? {
        if (rests.isEmpty()) return null
        val beta = rests.enzymeWork(BETA)
        val alpha = rests.enzymeWork(ALPHA)
        if (beta + alpha < MIN_CONVERSION_WORK) return null
        return beta / (beta + alpha)
    }

    // How much an enzyme converts over the whole schedule. Each rest contributes activity × time,
    // weighted by the enzyme still surviving (it denatures as rests get hot), integrated rest by rest
    // using the average of the surviving fraction before and after that rest.
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

    // Activity is a Gaussian bell around the enzyme's optimum temperature (full at the optimum, tailing
    // off either side over `widthC`).
    private fun Enzyme.activityAt(tempC: Double) = exp(-((tempC - optimumC) / widthC).pow(2))

    // Fraction of the enzyme surviving a rest: below its denature point it's stable; above, it decays
    // with an exponentially shorter half-life the hotter the rest.
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

    // Beta-amylase: makes maltose (fermentable), works cool, denatures early.
    private val BETA = Enzyme(optimumC = 63.0, widthC = 6.0, denaturesFromC = 63.0, decayPerC = 0.55)

    // Alpha-amylase: makes larger, less fermentable sugars, works hotter, hardier.
    private val ALPHA = Enzyme(optimumC = 72.0, widthC = 7.0, denaturesFromC = 73.0, decayPerC = 0.60)

    private const val BASE_HALF_LIFE_MIN = 1000.0

    private const val REFERENCE_MASH_C = 66.5
    private const val REFERENCE_MASH_MIN = 60

    // Full swing of the attenuation shift from an all-alpha to an all-beta mash (~±16.5 percentage pts).
    private const val ATTENUATION_SPAN = 0.165

    private const val MIN_CONVERSION_WORK = 1.0

    // The heart of the gravity maths: extract (kg of sugar) = Σ extract% × mass, at mash efficiency for
    // mashed grain (some is lost in the tun) but at 100% for sugars/extract (they dissolve fully). Then
    // points = extract × 384 / volume. Everything else here is built on this.
    private fun List<FermentableAddition>.gravityPoints(volumeL: Double, efficiency: Double): Double {
        require(volumeL > 0) { "volume must be > 0" }
        val extractKg = sumOf { (it.extractPercent / 100.0) * it.massKg * (if (it.mashed) efficiency else 1.0) }
        return extractKg * SUCROSE_POINTS_PER_KG_PER_L / volumeL
    }
}
