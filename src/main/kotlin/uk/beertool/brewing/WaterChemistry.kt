package uk.beertool.brewing

import kotlinx.serialization.SerialName
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

@Serializable
enum class Acid(
    val code: String,
    val label: String,
    val shortLabel: String,
    val meqPerMl: Double,
    val treatsAllWater: Boolean = false,
    val mineralsPerMlPerLitre: WaterProfile = WaterProfile.DISTILLED,
) {
    // 80% w/w at 1.18 g/ml is 10.5 mmol/ml, about 97% dissociated at mash pH.
    @SerialName("lactic80")
    LACTIC_80("lactic80", "Lactic acid 80%", "Lactic 80%", 10.2),

    // 75% w/w at 1.58 g/ml is 12.1 mmol/ml; at mash pH the first proton is given up and barely any of the second.
    @SerialName("phosphoric75")
    PHOSPHORIC_75("phosphoric75", "Phosphoric acid 75%", "Phosphoric 75%", 12.3),

    @SerialName("phosphoric10")
    PHOSPHORIC_10("phosphoric10", "Phosphoric acid 10%", "Phosphoric 10%", 1.09),

    // Murphy's TDS005 (2025): 5 ml per hL cuts alkalinity by 8.4 mg/L as CaCO3 and adds 3.1 mg/L chloride, 3.9 mg/L sulphate.
    @SerialName("ams")
    AMS("ams", "AMS (CRS)", "AMS", 3.37, treatsAllWater = true, mineralsPerMlPerLitre = WaterProfile(chloride = 62.0, sulphate = 78.0));

    companion object {
        fun fromCodeOrNull(code: String?) = entries.find { it.code == code }
    }
}

object WaterChemistry {
    // Troester measured 31-47 mEq/(kg·pH) for malts; one middle value stands in for the whole grist.
    private const val GRIST_BUFFER_MEQ_PER_KG_PH = 38.0

    fun blend(base: WaterProfile, other: WaterProfile, otherFraction: Double): WaterProfile {
        val f = otherFraction.coerceIn(0.0, 1.0)
        return base * (1.0 - f) + other * f
    }

    // The acid's own minerals join the water, and its neutralising cuts the bicarbonate, which cannot fall below none.
    fun withAcid(water: WaterProfile, acid: Acid, ml: Double, volumeL: Double): WaterProfile {
        require(volumeL > 0) { "volume must be > 0" }
        val perLitre = ml / volumeL
        val added = water + acid.mineralsPerMlPerLitre * perLitre
        return added.copy(bicarbonate = (added.bicarbonate - acid.meqPerMl * perLitre * HCO3_G_PER_MOL).coerceAtLeast(0.0))
    }

    fun withSalts(water: WaterProfile, salts: List<SaltAddition>, volumeL: Double): WaterProfile {
        require(volumeL > 0) { "volume must be > 0" }
        return salts.fold(water) { acc, s -> acc + s.salt.mgPerLitrePerGram * (s.grams / volumeL) }
    }

    // Fitted to Troester's distilled-water mash pH: about 5.75 for pale malt down to 4.7 for roasted.
    fun distilledWaterPh(colourEbc: Double) = 4.65 + 1.15 * exp(-colourEbc / 60.0)

    fun mashPh(grist: List<FermentableAddition>, water: WaterProfile, strikeL: Double, acidMeq: Double): Double? {
        val mashed = grist.filter { it.mashed && it.massKg > 0.0 }
        val kg = mashed.sumOf { it.massKg }
        if (kg <= 0.0 || strikeL <= 0.0) return null
        val distilledPh = mashed.sumOf { distilledWaterPh(it.colourEbc) * it.massKg } / kg
        val alkalinityMeq = water.residualAlkalinityMeqPerL * strikeL - acidMeq
        return distilledPh + alkalinityMeq / (GRIST_BUFFER_MEQ_PER_KG_PH * kg)
    }

    fun acidForPh(targetPh: Double, acid: Acid, grist: List<FermentableAddition>, water: WaterProfile, strikeL: Double): Double? {
        val kg = grist.filter { it.mashed }.sumOf { it.massKg }
        val withoutAcid = mashPh(grist, water, strikeL, acidMeq = 0.0) ?: return null
        val meq = (withoutAcid - targetPh) * GRIST_BUFFER_MEQ_PER_KG_PH * kg
        return (meq / acid.meqPerMl).coerceAtLeast(0.0)
    }
}
