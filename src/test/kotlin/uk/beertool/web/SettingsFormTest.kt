package uk.beertool.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.brewing.WaterProfile
import uk.beertool.brewing.Whirlpool
import uk.beertool.recipe.MashStep
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.WaterSource

class SettingsFormTest {

    @Test
    fun `should keep the current value where a scalar box is left blank`() {
        val current = BrewerPreferences(preBoilVolumeL = 30.0, efficiency = 0.68)

        val merged = SettingsForm(boilOffL = 6.0).toPreferences(current)

        merged.preBoilVolumeL shouldBe 30.0
        merged.efficiency shouldBe 0.68
        merged.boilOffL shouldBe 6.0
    }

    @Test
    fun `should take the whirlpool temperature and cooling time as entered`() {
        val merged = SettingsForm(whirlpoolTempC = 95.0, coolingTimeMin = 45).toPreferences(BrewerPreferences())

        merged.whirlpool shouldBe Whirlpool(tempC = 95.0, coolingMinutes = 45)
    }

    @Test
    fun `should read efficiency as a percentage`() {
        val merged = SettingsForm(efficiency = 70.0).toPreferences(BrewerPreferences())

        merged.efficiency shouldBe 0.70
    }

    @Test
    fun `should take the mash rests in the order they were entered`() {
        val form = SettingsForm(mashSteps = listOf(MashStepRow(62.0, 40), MashStepRow(72.0, 20)))

        val merged = form.toPreferences(BrewerPreferences())

        merged.mashSteps shouldBe listOf(MashStep(62.0, 40), MashStep(72.0, 20))
    }

    @Test
    fun `should drop a half-typed rest that names only a temperature or only a time`() {
        val form = SettingsForm(mashSteps = listOf(MashStepRow(66.0, 60), MashStepRow(tempC = 72.0), MashStepRow(timeMin = 15)))

        val merged = form.toPreferences(BrewerPreferences())

        merged.mashSteps shouldBe listOf(MashStep(66.0, 60))
    }

    @Test
    fun `should save each named water source and drop a row left without a name`() {
        val form = SettingsForm(
            waterSources = listOf(
                WaterSourceRow("London tap", calcium = 99.0, bicarbonate = 250.0),
                WaterSourceRow("", calcium = 10.0),
            ),
        )

        val merged = form.toPreferences(BrewerPreferences())

        merged.waterSources shouldBe listOf(WaterSource("London tap", WaterProfile(calcium = 99.0, bicarbonate = 250.0)))
    }

    @Test
    fun `should keep only the first of two water sources with the same name`() {
        val form = SettingsForm(waterSources = listOf(WaterSourceRow("Tap", calcium = 99.0), WaterSourceRow("Tap", calcium = 50.0)))

        val merged = form.toPreferences(BrewerPreferences())

        merged.waterSources.single().profile.calcium shouldBe 99.0
    }

    @Test
    fun `should clear the default mash when every rest has been removed`() {
        val current = BrewerPreferences(mashSteps = listOf(MashStep(66.0, 60)))

        val merged = SettingsForm(mashSteps = emptyList()).toPreferences(current)

        merged.mashSteps shouldBe emptyList()
    }

    @Test
    fun `should refuse losses that leave nothing for the fermenter`() {
        val form = SettingsForm(preBoilVolumeL = 10.0, boilOffL = 6.0, kettleRetentionL = 4.0)

        shouldThrow<InvalidInput> { form.toPreferences(BrewerPreferences()) }
    }

    @Test
    fun `should refuse a water figure too large to be real`() {
        val form = SettingsForm(waterSources = listOf(WaterSourceRow(name = "Tap", calcium = 1e9)))

        val error = shouldThrow<InvalidInput> { form.toPreferences(BrewerPreferences()) }

        error.message shouldBe "Tap calcium must be between 0 and 5000."
    }
}
