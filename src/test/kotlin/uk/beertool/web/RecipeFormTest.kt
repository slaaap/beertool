package uk.beertool.web

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.ktor.http.parametersOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.decodeFromStringMap
import org.junit.jupiter.api.Test
import uk.beertool.brewing.Acid
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.RecipeWater

class RecipeFormTest {

    @Test
    fun `should read efficiency and attenuation as percentages`() {
        val form = form("efficiency" to "68", "yeasts.0.name" to "US-05", "yeasts.0.attenuation" to "75")

        val recipe = form.toNewRecipe()

        recipe.efficiency shouldBe (0.68 plusOrMinus 0.0001)
        recipe.yeasts.single().attenuation shouldBe (0.75 plusOrMinus 0.0001)
    }

    @Test
    fun `should still understand a value typed as a fraction, out of old habit`() {
        val form = form("efficiency" to "0.68", "yeasts.0.name" to "US-05", "yeasts.0.attenuation" to "0.75")

        val recipe = form.toNewRecipe()

        recipe.efficiency shouldBe (0.68 plusOrMinus 0.0001)
        recipe.yeasts.single().attenuation shouldBe (0.75 plusOrMinus 0.0001)
    }

    @Test
    fun `should fall back to the app defaults when the fields are absent`() {
        val recipe = form().toNewRecipe()

        recipe.efficiency shouldBe 0.75
        recipe.postBoilVolumeL shouldBe 22.0
        recipe.fermenterVolumeL shouldBe 19.0
        recipe.boilTimeMin shouldBe 60
    }

    @Test
    fun `should read a blank box as not stated, never as zero`() {
        val form = form(
            "preBoilVolumeL" to "",
            "fermentables.0.name" to "Pilsner", "fermentables.0.amountKg" to "5",
            "fermentables.0.colourEbc" to "", "fermentables.0.extractPercent" to "",
        )

        val recipe = form.toNewRecipe()

        recipe.preBoilVolumeL shouldBe null
        recipe.fermentables.single().colourEbc shouldBe null
        recipe.fermentables.single().extractPercent shouldBe null
    }

    @Test
    fun `should decode an enum by the code it is stored under, not its Kotlin name`() {
        val form = form(
            "fermentables.0.name" to "Candi sugar", "fermentables.0.type" to "sugar",
            "hops.0.name" to "Citra", "hops.0.usage" to "dry_hop",
        )

        val recipe = form.toNewRecipe()

        recipe.fermentables.single().type shouldBe FermentableType.SUGAR
        recipe.hops.single().usage shouldBe HopUsage.DRY_HOP
    }

    @Test
    fun `should drop a row the brewer never named`() {
        val form = form(
            "hops.0.name" to "Magnum", "hops.0.amountG" to "30",
            "hops.1.name" to "", "hops.1.amountG" to "",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.single().name shouldBe "Magnum"
    }

    @Test
    fun `should keep every hop when a row is removed from the middle of the schedule`() {

        val form = form(
            "hops.0.name" to "Magnum",
            "hops.2.name" to "Citra",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.map { it.name } shouldBe listOf("Magnum", "Citra")
    }

    @Test
    fun `should not let a hop boil for longer than the boil`() {
        val form = form(
            "boilTimeMin" to "30",
            "hops.0.name" to "Magnum", "hops.0.usage" to "boil", "hops.0.boilTimeMin" to "60",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.single().boilTimeMin shouldBe 30
    }

    @Test
    fun `should leave a whirlpool stand alone, however long the boil was`() {
        val form = form(
            "boilTimeMin" to "20",
            "hops.0.name" to "Citra", "hops.0.usage" to "whirlpool", "hops.0.boilTimeMin" to "30",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.single().boilTimeMin shouldBe 30
    }

    @Test
    fun `should cap a first-wort hop, which boils for the whole boil`() {
        val form = form(
            "boilTimeMin" to "45",
            "hops.0.name" to "Saaz", "hops.0.usage" to "first_wort", "hops.0.boilTimeMin" to "90",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.single().boilTimeMin shouldBe 45
    }

    @Test
    fun `should cap every kind of boil addition, not just hops`() {
        val form = form(
            "boilTimeMin" to "30",
            "fermentables.0.name" to "Dextrose", "fermentables.0.type" to "sugar",
            "fermentables.0.usage" to "boil", "fermentables.0.boilTimeMin" to "60",
            "extras.0.name" to "Protafloc", "extras.0.usage" to "boil", "extras.0.boilTimeMin" to "45",
        )

        val recipe = form.toNewRecipe()

        recipe.fermentables.single().boilTimeMin shouldBe 30
        recipe.extras.single().boilTimeMin shouldBe 30
    }

    @Test
    fun `should keep a hop that fits inside the boil exactly as entered`() {
        val form = form(
            "boilTimeMin" to "60",
            "hops.0.name" to "Fuggles", "hops.0.usage" to "boil", "hops.0.boilTimeMin" to "15",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.single().boilTimeMin shouldBe 15
    }

    @Test
    fun `should read hops posted from several stage tables as one list in stage order`() {
        val form = form(
            "boilTimeMin" to "60",
            "hops.0.name" to "Magnum", "hops.0.usage" to "boil", "hops.0.boilTimeMin" to "60",
            "hops.1.name" to "Nelson Sauvin", "hops.1.usage" to "whirlpool", "hops.1.boilTimeMin" to "10",
            "hops.2.name" to "Nectaron", "hops.2.usage" to "dry_hop", "hops.2.dryHopDays" to "4",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.map { it.usage } shouldBe listOf(HopUsage.BOIL, HopUsage.WHIRLPOOL, HopUsage.DRY_HOP)
        recipe.hops.map { it.boilTimeMin } shouldBe listOf(60, 10, null)
        recipe.hops.map { it.dryHopDays } shouldBe listOf(null, null, 4)
    }

    @Test
    fun `should keep days in contact only for a dry hop, whatever stray value a kettle row carries`() {
        val form = form(
            "hops.0.name" to "Magnum", "hops.0.usage" to "boil", "hops.0.dryHopDays" to "4",
            "hops.1.name" to "Citra", "hops.1.usage" to "dry_hop", "hops.1.dryHopDays" to "3",
        )

        val recipe = form.toNewRecipe()

        recipe.hops.map { it.dryHopDays } shouldBe listOf(null, 3)
    }

    @Test
    fun `should read fermentables posted from several stage tables as one list in stage order`() {
        val form = form(
            "boilTimeMin" to "60",
            "fermentables.0.name" to "Maris Otter", "fermentables.0.usage" to "mash",
            "fermentables.0.type" to "malt", "fermentables.0.amountKg" to "5",
            "fermentables.1.name" to "Dextrose", "fermentables.1.usage" to "boil",
            "fermentables.1.type" to "sugar", "fermentables.1.boilTimeMin" to "10",
            "fermentables.2.name" to "Raspberry", "fermentables.2.usage" to "secondary",
            "fermentables.2.type" to "fruit", "fermentables.2.amountKg" to "2",
        )

        val recipe = form.toNewRecipe()

        recipe.fermentables.map { it.usage } shouldBe
            listOf(FermentableUsage.MASH, FermentableUsage.BOIL, FermentableUsage.SECONDARY)
        recipe.fermentables.map { it.type } shouldBe
            listOf(FermentableType.MALT, FermentableType.SUGAR, FermentableType.FRUIT)
        recipe.fermentables.map { it.boilTimeMin } shouldBe listOf(null, 10, null)
    }

    @Test
    fun `should read the water, its mix and its additions`() {
        val form = form(
            "water.source" to "London tap", "water.blendSource" to "Ashbeck", "water.blendPercent" to "40",
            "water.gypsumG" to "3.5", "water.calciumChlorideG" to "", "water.acidMl" to "4",
        )

        val water = form.toNewRecipe().water

        water shouldBe RecipeWater(source = "London tap", blendSource = "Ashbeck", blendPercent = 40.0, gypsumG = 3.5, acidMl = 4.0)
    }

    @Test
    fun `should read the chosen acid and fall back to lactic for an unknown one`() {
        val phosphoric = form("water.source" to "Tap", "water.acid" to "phosphoric75", "water.acidMl" to "3")
        val unknown = form("water.source" to "Tap", "water.acid" to "vinegar", "water.acidMl" to "3")

        phosphoric.toNewRecipe().water!!.acid shouldBe Acid.PHOSPHORIC_75
        unknown.toNewRecipe().water!!.acid shouldBe Acid.LACTIC_80
    }

    @Test
    fun `should forget the second water when none of it goes in the mix`() {
        val form = form("water.source" to "London tap", "water.blendSource" to "Ashbeck", "water.blendPercent" to "")

        val water = form.toNewRecipe().water

        water shouldBe RecipeWater(source = "London tap")
    }

    @Test
    fun `should leave a recipe without water when the water section was never opened`() {
        val form = form("name" to "Pale")

        form.toNewRecipe().water shouldBe null
    }

    @Test
    fun `should ignore a share of a second water that was never picked`() {
        val form = form("water.source" to "London tap", "water.blendPercent" to "50")

        form.toNewRecipe().water shouldBe RecipeWater(source = "London tap")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun form(vararg fields: Pair<String, String>): RecipeForm {
        val parameters = parametersOf(*fields.map { (k, v) -> k to listOf(v) }.toTypedArray())
        return Properties.decodeFromStringMap<RecipeForm>(parameters.toFormMap())
    }
}
