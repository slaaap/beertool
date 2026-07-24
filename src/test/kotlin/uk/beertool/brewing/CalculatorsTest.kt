package uk.beertool.brewing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CalculatorsTest {

    @Test
    fun `should calculate abv from og and fg`() {
        val og = 1.050
        val fg = 1.010

        val abv = Calculators.abv(og, fg)

        abv shouldBe (5.25 plusOrMinus 0.01)
    }

    @Test
    fun `should size strike and sparge water from grain, thickness and pre-boil volume`() {
        val water = Calculators.mashWater(
            grainKg = 5.0, firstRestTempC = 66.0, grainTempC = 20.0,
            preBoilVolumeL = 27.0, thicknessLPerKg = 3.0, absorptionLPerKg = 1.0,
        )

        water.strikeVolumeL shouldBe (15.0 plusOrMinus 0.01)
        water.spargeVolumeL shouldBe (17.0 plusOrMinus 0.01)
    }

    @Test
    fun `should heat strike water above the target rest so grain settles it to temperature`() {
        val strike = Calculators.strikeTempC(thicknessLPerKg = 3.0, targetRestC = 66.0, grainTempC = 20.0)

        strike shouldBeGreaterThan 66.0
        strike shouldBe (72.29 plusOrMinus 0.1)
    }

    @Test
    fun `should never ask for negative sparge water when the mash already holds enough`() {
        val water = Calculators.mashWater(
            grainKg = 5.0, firstRestTempC = 66.0, grainTempC = 20.0,
            preBoilVolumeL = 10.0, thicknessLPerKg = 3.0, absorptionLPerKg = 1.0,
        )

        water.spargeVolumeL shouldBe 0.0
    }

    @Test
    fun `should apply attenuation when estimating fg`() {
        val bill = listOf(FermentableAddition(extractPercent = 80.0, colourEbc = 6.0, massKg = 4.5))

        val og = Calculators.estimateOg(bill, volumeL = 20.0, efficiency = 0.75)
        val fg = Calculators.estimateFg(bill, volumeL = 20.0, efficiency = 0.75, attenuation = 0.75)

        fg shouldBe (1.0 + (og - 1.0) * 0.25 plusOrMinus 0.0001)
    }

    @Test
    fun `should increase estimated og with more malt`() {
        val lessMalt = listOf(FermentableAddition(80.0, 6.0, 3.0))
        val moreMalt = listOf(FermentableAddition(80.0, 6.0, 6.0))

        val less = Calculators.estimateOg(lessMalt, 20.0, 0.75)
        val more = Calculators.estimateOg(moreMalt, 20.0, 0.75)

        more shouldBeGreaterThan less
    }

    @Test
    fun `should increase ibu with longer boil time`() {
        val shortBoil = listOf(HopAddition(5.5, 30.0, 15))
        val longBoil = listOf(HopAddition(5.5, 30.0, 60))

        val shortIbu = Calculators.ibu(shortBoil, 20.0, 1.050)
        val longIbu = Calculators.ibu(longBoil, 20.0, 1.050)

        shortIbu shouldBeGreaterThan 0.0
        longIbu shouldBeGreaterThan shortIbu
    }

    @Test
    fun `should estimate beer colour in ebc in the pale-ale range for a light malt bill`() {
        val bill = listOf(FermentableAddition(extractPercent = 80.0, colourEbc = 6.0, massKg = 4.5))

        val colour = Calculators.colourEbc(bill, 20.0)

        colour shouldBeIn 6.0..16.0
    }

    @Test
    fun `should reject a zero volume`() {
        val bill = listOf(FermentableAddition(80.0, 6.0, 4.5))

        shouldThrow<IllegalArgumentException> {
            Calculators.estimateOg(bill, 0.0, 0.75)
        }
    }
}
