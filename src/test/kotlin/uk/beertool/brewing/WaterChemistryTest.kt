package uk.beertool.brewing

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WaterChemistryTest {

    private val londonTap = WaterProfile(calcium = 99.0, magnesium = 4.5, sodium = 30.0, chloride = 45.0, sulphate = 47.0, bicarbonate = 250.0)
    private val paleGrist = listOf(FermentableAddition(extractPercent = 80.0, colourEbc = 6.0, massKg = 5.0))

    @Test
    fun `should add the published ion yield of a gram of gypsum in a litre`() {
        val salts = listOf(SaltAddition(Salt.GYPSUM, 1.0))

        val water = WaterChemistry.withSalts(WaterProfile.DISTILLED, salts, volumeL = 1.0)

        water.calcium shouldBe (232.8 plusOrMinus 0.5)
        water.sulphate shouldBe (557.9 plusOrMinus 0.5)
    }

    @Test
    fun `should count calcium chloride flakes as the dihydrate`() {
        val salts = listOf(SaltAddition(Salt.CALCIUM_CHLORIDE, 1.0))

        val water = WaterChemistry.withSalts(WaterProfile.DISTILLED, salts, volumeL = 1.0)

        water.calcium shouldBe (272.6 plusOrMinus 0.5)
        water.chloride shouldBe (482.3 plusOrMinus 0.5)
    }

    @Test
    fun `should spread salts over the whole volume they are dissolved in`() {
        val salts = listOf(SaltAddition(Salt.TABLE_SALT, 2.0))

        val water = WaterChemistry.withSalts(londonTap, salts, volumeL = 20.0)

        water.sodium shouldBe (30.0 + 2.0 * 393.4 / 20.0 plusOrMinus 0.1)
        water.calcium shouldBe 99.0
    }

    @Test
    fun `should mix two waters in proportion`() {
        val soft = WaterProfile(calcium = 11.0, bicarbonate = 25.0)

        val mixed = WaterChemistry.blend(londonTap, soft, otherFraction = 0.4)

        mixed.calcium shouldBe (99.0 * 0.6 + 11.0 * 0.4 plusOrMinus 0.001)
        mixed.bicarbonate shouldBe (250.0 * 0.6 + 25.0 * 0.4 plusOrMinus 0.001)
    }

    @Test
    fun `should let calcium cancel part of hard water's alkalinity`() {
        val ra = londonTap.residualAlkalinityMeqPerL

        ra shouldBe (2.64 plusOrMinus 0.02)
        ra shouldBeLessThan londonTap.bicarbonate / 61.017
    }

    @Test
    fun `should land a pale grist near its distilled water pH in distilled water`() {
        val ph = WaterChemistry.mashPh(paleGrist, WaterProfile.DISTILLED, strikeL = 15.0, acidMeq = 0.0)!!

        ph shouldBe (5.70 plusOrMinus 0.05)
    }

    @Test
    fun `should give a dark grist a lower mash pH than a pale one`() {
        val dark = paleGrist + FermentableAddition(extractPercent = 70.0, colourEbc = 1000.0, massKg = 0.5)

        val palePh = WaterChemistry.mashPh(paleGrist, WaterProfile.DISTILLED, 15.0, 0.0)!!
        val darkPh = WaterChemistry.mashPh(dark, WaterProfile.DISTILLED, 16.5, 0.0)!!

        darkPh shouldBeLessThan palePh - 0.05
    }

    @Test
    fun `should raise mash pH by about Kolbach's 0_3 for 10 degrees German of residual alkalinity`() {
        val alkaline = WaterProfile(bicarbonate = 10 * 17.8 * 1.22)

        val distilled = WaterChemistry.mashPh(paleGrist, WaterProfile.DISTILLED, strikeL = 15.0, acidMeq = 0.0)!!
        val hard = WaterChemistry.mashPh(paleGrist, alkaline, strikeL = 15.0, acidMeq = 0.0)!!

        hard - distilled shouldBe (0.3 plusOrMinus 0.05)
    }

    @Test
    fun `should bring the mash pH down as lactic acid goes in`() {
        val without = WaterChemistry.mashPh(paleGrist, londonTap, 15.0, acidMeq = 0.0)!!
        val with = WaterChemistry.mashPh(paleGrist, londonTap, 15.0, acidMeq = 5.0 * Acid.LACTIC_80.meqPerMl)!!

        without shouldBeGreaterThan 5.8
        with shouldBeLessThan without - 0.2
    }

    @Test
    fun `should suggest the acid that hits the target pH exactly, whichever acid it is`() {
        val ph = Acid.entries.map { acid ->
            val ml = WaterChemistry.acidForPh(5.4, acid, paleGrist, londonTap, strikeL = 15.0)!!

            WaterChemistry.mashPh(paleGrist, londonTap, 15.0, ml * acid.meqPerMl)!!
        }

        ph.forEach { it shouldBe (5.4 plusOrMinus 0.001) }
    }

    @Test
    fun `should need a little less strong phosphoric than lactic, and far more of the dilute one`() {
        val ml = Acid.entries.associateWith { WaterChemistry.acidForPh(5.4, it, paleGrist, londonTap, strikeL = 15.0)!! }

        ml.getValue(Acid.PHOSPHORIC_75) shouldBeLessThan ml.getValue(Acid.LACTIC_80)
        ml.getValue(Acid.PHOSPHORIC_10) shouldBeGreaterThan ml.getValue(Acid.LACTIC_80) * 9
    }

    @Test
    fun `should suggest no acid for a mash already below the target`() {
        val roast = listOf(FermentableAddition(extractPercent = 70.0, colourEbc = 1000.0, massKg = 5.0))

        val ml = WaterChemistry.acidForPh(5.4, Acid.LACTIC_80, roast, WaterProfile.DISTILLED, strikeL = 15.0)

        ml shouldBe 0.0
    }

    @Test
    fun `should suggest salts that land soft water on both the calcium and the ratio`() {
        val soft = WaterProfile(calcium = 17.0, magnesium = 5.0, sodium = 10.0, chloride = 13.0, sulphate = 14.0, bicarbonate = 46.0)

        val salts = WaterChemistry.saltsFor(soft, calcium = 100.0, chlorideToSulphate = 0.5, volumeL = 32.0)

        val treated = WaterChemistry.withSalts(soft, listOf(SaltAddition(Salt.GYPSUM, salts.gypsumG), SaltAddition(Salt.CALCIUM_CHLORIDE, salts.calciumChlorideG)), 32.0)
        treated.calcium shouldBe (100.0 plusOrMinus 0.01)
        treated.chlorideToSulphate!! shouldBe (0.5 plusOrMinus 0.001)
    }

    @Test
    fun `should only fix the ratio, with the one salt it needs, when the water has calcium enough`() {
        val salts = WaterChemistry.saltsFor(londonTap, calcium = 75.0, chlorideToSulphate = 2.0, volumeL = 32.0)

        val treated = WaterChemistry.withSalts(londonTap, listOf(SaltAddition(Salt.CALCIUM_CHLORIDE, salts.calciumChlorideG)), 32.0)
        salts.gypsumG shouldBe 0.0
        treated.chlorideToSulphate!! shouldBe (2.0 plusOrMinus 0.001)
    }

    @Test
    fun `should reach a bitter ratio with gypsum alone when chloride is already too high for it`() {
        val salts = WaterChemistry.saltsFor(londonTap, calcium = 100.0, chlorideToSulphate = 0.5, volumeL = 32.0)

        val treated = WaterChemistry.withSalts(londonTap, listOf(SaltAddition(Salt.GYPSUM, salts.gypsumG)), 32.0)
        salts.calciumChlorideG shouldBe 0.0
        treated.chlorideToSulphate!! shouldBe (0.5 plusOrMinus 0.001)
        treated.calcium shouldBeGreaterThan 100.0
    }

    @Test
    fun `should not guess a mash pH when nothing is mashed`() {
        val extract = listOf(FermentableAddition(extractPercent = 95.0, colourEbc = 15.0, massKg = 3.0, mashed = false))

        WaterChemistry.mashPh(extract, londonTap, strikeL = 15.0, acidMeq = 0.0).shouldBeNull()
        WaterChemistry.acidForPh(5.4, Acid.LACTIC_80, extract, londonTap, strikeL = 15.0).shouldBeNull()
    }
}
