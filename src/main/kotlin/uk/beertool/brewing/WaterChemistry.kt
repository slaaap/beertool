package uk.beertool.brewing

import kotlinx.serialization.Serializable
import kotlin.math.exp

@Serializable
data class WaterProfile(
    val calcium: Double = 0.0,
    val magnesium: Double = 0.0,
    val sodium: Double = 0.0,
    val chloride: Double = 0.0,
    val sulphate: Double = 0.0,
    val bicarbonate: Double = 0.0,
) {
    operator fun plus(o: WaterProfile) = WaterProfile(
        calcium + o.calcium,
        magnesium + o.magnesium,
        sodium + o.sodium,
        chloride + o.chloride,
        sulphate + o.sulphate,
        bicarbonate + o.bicarbonate,
    )

    operator fun times(k: Double) =
        WaterProfile(calcium * k, magnesium * k, sodium * k, chloride * k, sulphate * k, bicarbonate * k)

    // Kolbach: calcium and magnesium free acid from malt phosphate, cancelling part of the alkalinity.
    val residualAlkalinityMeqPerL: Double
        get() = bicarbonate / HCO3_G_PER_MOL - (calcium / (CA_G_PER_MOL / 2)) / 3.5 - (magnesium / (MG_G_PER_MOL / 2)) / 7.0

    val chlorideToSulphate: Double? get() = if (sulphate > 0.0) chloride / sulphate else null

    companion object {
        val DISTILLED = WaterProfile()
    }
}

private const val CA_G_PER_MOL = 40.078
private const val MG_G_PER_MOL = 24.305
private const val NA_G_PER_MOL = 22.990
private const val CL_G_PER_MOL = 35.453
private const val SO4_G_PER_MOL = 96.06
private const val HCO3_G_PER_MOL = 61.017

enum class Salt(private val molarMass: Double, private val ions: WaterProfile) {
    GYPSUM(172.17, WaterProfile(calcium = CA_G_PER_MOL, sulphate = SO4_G_PER_MOL)),

    // Sold as flakes, which are the dihydrate.
    CALCIUM_CHLORIDE(147.01, WaterProfile(calcium = CA_G_PER_MOL, chloride = 2 * CL_G_PER_MOL)),
    EPSOM(246.47, WaterProfile(magnesium = MG_G_PER_MOL, sulphate = SO4_G_PER_MOL)),
    TABLE_SALT(58.44, WaterProfile(sodium = NA_G_PER_MOL, chloride = CL_G_PER_MOL)),
    BAKING_SODA(84.007, WaterProfile(sodium = NA_G_PER_MOL, bicarbonate = HCO3_G_PER_MOL));

    val mgPerLitrePerGram get() = ions * (1000.0 / molarMass)
}

data class SaltAddition(val salt: Salt, val grams: Double)

object WaterChemistry {
    // Lactic acid 80% w/w at 1.18 g/ml is 10.5 mmol/ml, about 97% dissociated at mash pH.
    const val LACTIC_MEQ_PER_ML = 10.2

    // Troester measured 31-47 mEq/(kg·pH) for malts; one middle value stands in for the whole grist.
    private const val GRIST_BUFFER_MEQ_PER_KG_PH = 38.0

    fun blend(base: WaterProfile, other: WaterProfile, otherFraction: Double): WaterProfile {
        val f = otherFraction.coerceIn(0.0, 1.0)
        return base * (1.0 - f) + other * f
    }

    fun withSalts(water: WaterProfile, salts: List<SaltAddition>, volumeL: Double): WaterProfile {
        require(volumeL > 0) { "volume must be > 0" }
        return salts.fold(water) { acc, s -> acc + s.salt.mgPerLitrePerGram * (s.grams / volumeL) }
    }

    // Fitted to Troester's distilled-water mash pH: about 5.75 for pale malt down to 4.7 for roasted.
    fun distilledWaterPh(colourEbc: Double) = 4.65 + 1.15 * exp(-colourEbc / 60.0)

    fun mashPh(grist: List<FermentableAddition>, water: WaterProfile, strikeL: Double, lacticMl: Double): Double? {
        val mashed = grist.filter { it.mashed && it.massKg > 0.0 }
        val kg = mashed.sumOf { it.massKg }
        if (kg <= 0.0 || strikeL <= 0.0) return null
        val distilledPh = mashed.sumOf { distilledWaterPh(it.colourEbc) * it.massKg } / kg
        val alkalinityMeq = water.residualAlkalinityMeqPerL * strikeL - lacticMl * LACTIC_MEQ_PER_ML
        return distilledPh + alkalinityMeq / (GRIST_BUFFER_MEQ_PER_KG_PH * kg)
    }

    fun lacticForPh(targetPh: Double, grist: List<FermentableAddition>, water: WaterProfile, strikeL: Double): Double? {
        val kg = grist.filter { it.mashed }.sumOf { it.massKg }
        val withoutAcid = mashPh(grist, water, strikeL, lacticMl = 0.0) ?: return null
        val meq = (withoutAcid - targetPh) * GRIST_BUFFER_MEQ_PER_KG_PH * kg
        return (meq / LACTIC_MEQ_PER_ML).coerceAtLeast(0.0)
    }
}
