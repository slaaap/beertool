package uk.beertool.recipe

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.brewing.Acid
import uk.beertool.brewing.WaterProfile
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.WaterSource

class RecipeWaterTest {

    private val tap = WaterSource("London tap", WaterProfile(calcium = 99.0, magnesium = 4.5, sodium = 30.0, chloride = 45.0, sulphate = 47.0, bicarbonate = 250.0))
    private val ashbeck = WaterSource("Ashbeck", WaterProfile(calcium = 11.0, magnesium = 3.5, sodium = 10.0, chloride = 14.0, sulphate = 11.0, bicarbonate = 25.0))
    private val prefs = BrewerPreferences(
        preBoilVolumeL = 27.0,
        mashThicknessLPerKg = 3.0,
        grainAbsorptionLPerKg = 1.0,
        waterSources = listOf(tap, ashbeck),
    )

    private fun pale(water: RecipeWater?) = NewRecipe(
        name = "Pale",
        preBoilVolumeL = 27.0,
        fermentables = listOf(RecipeFermentable(name = "Maris Otter", amountKg = 5.0, colourEbc = 6.0)),
        water = water,
    )

    @Test
    fun `should have nothing to report for a recipe that says nothing about water`() {
        pale(water = null).waterReport(prefs).shouldBeNull()
    }

    @Test
    fun `should dose salts across both strike and sparge water`() {
        val report = pale(RecipeWater(source = "Ashbeck", gypsumG = 3.2)).waterReport(prefs)!!

        report.strikeL shouldBe (15.0 plusOrMinus 0.001)
        report.spargeL shouldBe (17.0 plusOrMinus 0.001)
        report.totalL shouldBe (32.0 plusOrMinus 0.001)
        report.profile.calcium shouldBe (11.0 + 3.2 * 232.8 / 32.0 plusOrMinus 0.1)
    }

    @Test
    fun `should look up a named water in the brewer's sources and mix in a second`() {
        val report = pale(RecipeWater(source = "London tap", blendSource = "Ashbeck", blendPercent = 50.0)).waterReport(prefs)!!

        report.profile.calcium shouldBe (55.0 plusOrMinus 0.001)
        report.missingSources shouldBe emptyList()
    }

    @Test
    fun `should not mix when no second water is named`() {
        val report = pale(RecipeWater(source = "London tap", blendPercent = 25.0)).waterReport(prefs)!!

        report.profile.bicarbonate shouldBe 250.0
    }

    @Test
    fun `should leave out the minerals of a water that was deleted from settings and say so`() {
        val report = pale(RecipeWater(source = "Old well")).waterReport(prefs)!!

        report.profile shouldBe WaterProfile.DISTILLED
        report.warnings shouldContain "\"Old well\" is no longer in your water sources, so its minerals are left out."
    }

    @Test
    fun `should warn that untreated London tap leaves a pale mash too alkaline`() {
        val report = pale(RecipeWater(source = "London tap")).waterReport(prefs)!!

        report.mashPh!! shouldBe (5.91 plusOrMinus 0.03)
        report.acidForTargetMl!! shouldBe (9.5 plusOrMinus 0.3)
        report.warnings shouldContain "Mash pH is above 5.6: expect a harsher, duller beer. Add acid."
    }

    @Test
    fun `should call a sulphate-heavy water bitter and a chloride-heavy one malty`() {
        val bitter = pale(RecipeWater(source = "London tap", gypsumG = 6.0)).waterReport(prefs)!!
        val malty = pale(RecipeWater(source = "London tap", calciumChlorideG = 6.0)).waterReport(prefs)!!

        bitter.balance shouldBe FlavourBalance.BITTER
        malty.balance shouldBe FlavourBalance.MALTY
    }

    @Test
    fun `should dose an extract brew over the pre-boil volume with no mash pH`() {
        val extract = NewRecipe(
            name = "Kit",
            preBoilVolumeL = 25.0,
            fermentables = listOf(RecipeFermentable(name = "Light DME", amountKg = 3.0, type = FermentableType.EXTRACT, usage = FermentableUsage.BOIL)),
            water = RecipeWater(source = "Ashbeck", gypsumG = 2.0),
        )

        val report = extract.waterReport(prefs)!!

        report.totalL shouldBe 25.0
        report.mashPh.shouldBeNull()
        report.strikeL.shouldBeNull()
    }

    @Test
    fun `should split salts by volume between strike and sparge and put all the acid in the mash`() {
        val water = RecipeWater(source = "London tap", gypsumG = 3.2, acidMl = 5.0)

        val doses = pale(water).waterReport(prefs)!!.doses(water)

        doses.map { it.addition } shouldContainExactly listOf(WaterAddition.GYPSUM, WaterAddition.ACID)
        doses[0].strike shouldBe (1.5 plusOrMinus 0.001)
        doses[0].sparge shouldBe (1.7 plusOrMinus 0.001)
        doses[1].strike shouldBe 5.0
        doses[1].sparge shouldBe 0.0
    }

    @Test
    fun `should give each Campden tablet share its volume and leave the minerals and pH alone`() {
        val plain = RecipeWater(source = "London tap")
        val treated = plain.copy(campdenTablets = 1.0)

        val report = pale(treated).waterReport(prefs)!!

        report.doses(treated).single().strike shouldBe (15.0 / 32.0 plusOrMinus 0.001)
        report.profile shouldBe pale(plain).waterReport(prefs)!!.profile
        report.mashPh shouldBe pale(plain).waterReport(prefs)!!.mashPh
    }

    @Test
    fun `should suggest the acid amount in the acid the recipe uses`() {
        val lactic = pale(RecipeWater(source = "London tap")).waterReport(prefs)!!
        val phosphoric = pale(RecipeWater(source = "London tap", acid = Acid.PHOSPHORIC_10)).waterReport(prefs)!!

        phosphoric.acid shouldBe Acid.PHOSPHORIC_10
        phosphoric.acidForTargetMl!! shouldBe (lactic.acidForTargetMl!! * Acid.LACTIC_80.meqPerMl / Acid.PHOSPHORIC_10.meqPerMl plusOrMinus 0.01)
    }

    @Test
    fun `should describe a mix with each water's share`() {
        val water = RecipeWater(source = "London tap", blendSource = "Ashbeck", blendPercent = 40.0)

        water.describe() shouldBe "London tap 60% + Ashbeck 40%"
    }

    @Test
    fun `should list only the salts actually used`() {
        val water = RecipeWater(source = "London tap", gypsumG = 2.0, epsomG = 0.0, bakingSodaG = 1.0)

        water.salts.map { it.salt.name } shouldContainExactly listOf("GYPSUM", "BAKING_SODA")
    }
}
