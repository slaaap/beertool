package uk.beertool.brewing

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BoilGravityTest {

    @Test
    fun `should average the pre-boil and post-boil gravity, because the wort boils down`() {

        val average = Calculators.averageBoilGravity(og = 1.050, postBoilVolumeL = 22.0, preBoilVolumeL = 27.0)

        average shouldBe (1.0 + (50.0 + 50.0 * 22.0 / 27.0) / 2.0 / 1000.0 plusOrMinus 0.0001)
    }

    @Test
    fun `should fall back to the OG when the pre-boil volume is unknown`() {
        Calculators.averageBoilGravity(1.050, 22.0, null) shouldBe 1.050

        Calculators.averageBoilGravity(1.050, 22.0, 20.0) shouldBe 1.050
    }

    @Test
    fun `should get more bitterness out of the same hops once the boil-off is known`() {
        val hops = listOf(HopAddition(alphaAcidPercent = 12.0, massGrams = 30.0, boilTimeMinutes = 60))

        val ignoringBoilOff = Calculators.ibu(hops, volumeL = 22.0, boilGravity = 1.050)
        val knowingBoilOff = Calculators.ibu(
            hops,
            volumeL = 22.0,
            boilGravity = Calculators.averageBoilGravity(1.050, 22.0, 27.0),
        )

        knowingBoilOff shouldBeGreaterThan ignoringBoilOff
    }
}
