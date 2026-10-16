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
import java.time.LocalDate

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

        card shouldContain "• 20 g Magnum 12.5% · boil 60 min"
        card shouldContain "• 50 g Citra 13% · dry hop 4 days"
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

        val card = brewDayCard(recipe, sampleBatch(), BrewerPreferences(), today)

        card shouldContain "Strike 13.5 L at"
    }

    @Test
    fun `should line up logged readings against the plan and leave out the ones still to come`() {
        val recipe = recipe()
        val batch = sampleBatch(measuredOg = 1.052, measuredPostBoilVolumeL = 22.0)

        val card = batchCard(recipe, batch, recipe.stats(), today)

        card shouldContain "<pre>" + "           Actual   Plan\n" +
            "OG          1.052  ${"%.3f".format(recipe.stats().og)}\n" +
            "Post-boil    22.0   23.0</pre>"
        card shouldNotContain "Pre-boil"
    }

    @Test
    fun `should say what is still to log and how to send it`() {
        val recipe = recipe()
        val batch = sampleBatch(measuredOg = 1.052)

        val card = batchCard(recipe, batch, recipe.stats(), today)

        card shouldContain "Still to log: fg, pre, post, fermenter. Send e.g. <code>fg 1.010</code>"
    }

    @Test
    fun `should count the days a batch has been fermenting`() {
        val recipe = recipe()
        val batch = sampleBatch(brewDate = LocalDate.of(2026, 10, 7), measuredOg = 1.052)

        val card = batchCard(recipe, batch, recipe.stats(), today)

        card shouldContain "🫧"
        card shouldContain "Fermenting · day 9 · brewed 7 Oct"
    }

    @Test
    fun `should count the days in the bottle once packaged and stop asking for readings`() {
        val recipe = recipe()
        val batch = sampleBatch(brewDate = LocalDate.of(2025, 12, 1), packagedDate = LocalDate.of(2026, 10, 1), measuredOg = 1.052)

        val card = batchCard(recipe, batch, recipe.stats(), today)

        card shouldContain "Packaged 1 Oct · 15 days in the bottle · brewed 1 Dec 2025"
        card shouldNotContain "Still to log"
    }

    @Test
    fun `should open with ABV, attenuation and efficiency when they are known`() {
        val recipe = recipe()
        val batch = sampleBatch(measuredOg = 1.050, measuredFg = 1.010, abv = 5.25, mashEfficiency = 0.71)

        val card = batchCard(recipe, batch, recipe.stats(), today)

        card shouldContain "<b>5.3% ABV · 80% attenuation · 71% efficiency</b>"
    }

    @Test
    fun `should link the batch name to its page on the site`() {
        val recipe = recipe()

        val card = batchCard(recipe, sampleBatch(), recipe.stats(), today, publicUrl = "https://beer.example")

        card shouldContain "<a href=\"https://beer.example/recipes/7/batches/1\">Pale Ale #7 · batch 1</a>"
    }

    @Test
    fun `should fold notes away in an expandable quote`() {
        val recipe = recipe()
        val batch = sampleBatch(notes = "Mash ran hot <5 min>\nDry hopped day 3")

        val card = batchCard(recipe, batch, recipe.stats(), today)

        card shouldContain "<blockquote expandable>Mash ran hot &lt;5 min&gt;\nDry hopped day 3</blockquote>"
    }

    private val today = LocalDate.of(2026, 10, 16)

    private fun sampleBatch(
        brewDate: LocalDate? = null,
        packagedDate: LocalDate? = null,
        measuredOg: Double? = null,
        measuredFg: Double? = null,
        measuredPostBoilVolumeL: Double? = null,
        abv: Double? = null,
        mashEfficiency: Double? = null,
        notes: String? = null,
    ) = Batch(
        id = 1, recipeId = 1, userId = 1, no = 1, brewDate = brewDate, packagedDate = packagedDate,
        measuredOg = measuredOg, measuredFg = measuredFg, measuredPostBoilVolumeL = measuredPostBoilVolumeL,
        notes = notes, abv = abv, mashEfficiency = mashEfficiency, createdAt = Instant.EPOCH,
    )
}
