package uk.beertool.telegram

import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import uk.beertool.batch.Batch
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.stats
import uk.beertool.user.BrewerPreferences
import java.time.Instant

class CardsTest {

    private fun recipe(name: String = "Pale Ale", hops: List<RecipeHop> = emptyList()) = Recipe(
        id = 1, userId = 1, no = 7, name = name, style = "American IPA", description = null,
        preBoilVolumeL = 27.0, postBoilVolumeL = 23.0, fermenterVolumeL = 21.0, efficiency = 0.72, boilTimeMin = 60,
        mashSteps = listOf(MashStep(66.0, 60)),
        fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5)),
        hops = hops, yeasts = emptyList(), extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    @Test
    fun `should escape recipe names so they cannot break the message markup`() {
        val recipe = recipe(name = "<b>Smash & Grab</b>")

        val card = recipeCard(recipe, recipe.stats())

        card shouldContain "&lt;b&gt;Smash &amp; Grab&lt;/b&gt;"
        card shouldNotContain "<b><b>"
    }

    @Test
    fun `should list hops with how and when they go in`() {
        val recipe = recipe(
            hops = listOf(
                RecipeHop(name = "Magnum", amountG = 20.0, alphaAcid = 12.5, usage = HopUsage.BOIL, boilTimeMin = 60),
                RecipeHop(name = "Citra", amountG = 50.0, alphaAcid = 13.0, usage = HopUsage.DRY_HOP, dryHopDays = 4),
            ),
        )

        val card = recipeCard(recipe, recipe.stats())

        card shouldContain "20 g Magnum (12.5%) · boil 60 min"
        card shouldContain "50 g Citra (13%) · dry hop 4 days"
    }

    @Test
    fun `should keep a huge recipe within the Telegram message limit`() {
        val recipe = recipe(hops = List(300) { RecipeHop(name = "Hop number $it", amountG = 10.0, alphaAcid = 5.0) })

        val card = recipeCard(recipe, recipe.stats())

        card.length shouldBeLessThan 4096
    }

    @Test
    fun `should give the strike water on the brew day card`() {
        val recipe = recipe()

        val card = brewDayCard(recipe, sampleBatch(), BrewerPreferences())

        card shouldContain "Strike 13.5 L at"
    }

    private fun sampleBatch() = Batch(
        id = 1, recipeId = 1, userId = 1, no = 1, brewDate = null, packagedDate = null,
        measuredOg = null, measuredFg = null, abv = null, mashEfficiency = null, createdAt = Instant.EPOCH,
    )
}
