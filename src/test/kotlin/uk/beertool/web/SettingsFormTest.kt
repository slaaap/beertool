package uk.beertool.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.recipe.MashStep
import uk.beertool.user.BrewerPreferences

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
    fun `should clear the default mash when every rest has been removed`() {
        val current = BrewerPreferences(mashSteps = listOf(MashStep(66.0, 60)))

        val merged = SettingsForm(mashSteps = emptyList()).toPreferences(current)

        merged.mashSteps shouldBe emptyList()
    }
}
