package uk.beertool.user

import kotlinx.serialization.Serializable
import uk.beertool.recipe.BOIL_OFF_L
import uk.beertool.recipe.DEFAULT_BOIL_TIME_MIN
import uk.beertool.recipe.DEFAULT_EFFICIENCY
import uk.beertool.recipe.DEFAULT_GRAIN_ABSORPTION_L_PER_KG
import uk.beertool.recipe.DEFAULT_MASH_STEPS
import uk.beertool.recipe.DEFAULT_MASH_THICKNESS_L_PER_KG
import uk.beertool.recipe.DEFAULT_PRE_BOIL_VOLUME_L
import uk.beertool.recipe.KETTLE_RETENTION_L
import uk.beertool.recipe.MashStep

@Serializable
data class BrewerPreferences(
    val preBoilVolumeL: Double = DEFAULT_PRE_BOIL_VOLUME_L,
    val boilOffL: Double = BOIL_OFF_L,
    val kettleRetentionL: Double = KETTLE_RETENTION_L,

    val efficiency: Double = DEFAULT_EFFICIENCY,
    val boilTimeMin: Int = DEFAULT_BOIL_TIME_MIN,

    val mashSteps: List<MashStep> = DEFAULT_MASH_STEPS,

    val mashThicknessLPerKg: Double = DEFAULT_MASH_THICKNESS_L_PER_KG,
    val grainAbsorptionLPerKg: Double = DEFAULT_GRAIN_ABSORPTION_L_PER_KG,
) {
    val postBoilVolumeL: Double get() = preBoilVolumeL - boilOffL
    val fermenterVolumeL: Double get() = postBoilVolumeL - kettleRetentionL
}
