package uk.beertool.telegram

import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import uk.beertool.batch.Batch
import uk.beertool.batch.BatchSummary
import uk.beertool.brewing.WaterProfile
import uk.beertool.common.formatAmount
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.WaterSnapshot
import uk.beertool.recipe.stats
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.WaterSource
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
    fun `should give the brew's water treatment split between strike and sparge`() {
        val tap = WaterSource("London tap", WaterProfile(calcium = 99.0, bicarbonate = 250.0))
        val water = WaterSnapshot(RecipeWater(source = "London tap", gypsumG = 3.5, lacticMl = 4.0), listOf(tap))

        val card = brewDayCard(recipe(), sampleBatch(water = water), BrewerPreferences(), today)

        card shouldContain "<i>London tap</i>"
        card shouldContain "• Gypsum: 1.5 g strike + 2.0 g sparge"
        card shouldContain "• Lactic acid 80%: 4.0 ml in the mash"
        card shouldContain "Mash pH about"
    }

    @Test
    fun `should leave water treatment off a brew that has none`() {
        val card = brewDayCard(recipe(), sampleBatch(), BrewerPreferences(), today)

        card shouldNotContain "Mash pH"
    }

    @Test
    fun `should give the plan next to a reading only where they differ`() {
        val recipe = recipe()
        val estimate = recipe.stats()
        val batch = sampleBatch(measuredOg = 1.052, measuredFg = estimate.fg, measuredPostBoilVolumeL = 22.0)

        val card = batchCard(recipe, batch, estimate, today)

        card shouldContain "OG 1.052 (plan ${"%.3f".format(estimate.og)}) · FG ${"%.3f".format(estimate.fg)}\n"
        card shouldContain "Post-boil 22 L (plan 23 L)"
        card shouldNotContain "Pre-boil"
        card shouldNotContain "<pre>"
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
        card shouldContain "Brewed 7 Oct · fermenting, day 9"
    }

    @Test
    fun `should say when a batch was packaged and stop asking for readings`() {
        val recipe = recipe()
        val batch = sampleBatch(brewDate = LocalDate.of(2025, 12, 1), packagedDate = LocalDate.of(2026, 10, 1), measuredOg = 1.052)

        val card = batchCard(recipe, batch, recipe.stats(), today)

        card shouldContain "Brewed 1 Dec 2025 · packaged 1 Oct"
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

    @Test
    fun `should list the brew log date first and mark the brews still going`() {
        val packaged = BatchSummary(sampleBatch(brewDate = LocalDate.of(2026, 9, 1), packagedDate = LocalDate.of(2026, 9, 20)), "Pale Ale", 7)
        val fermenting = BatchSummary(sampleBatch(brewDate = LocalDate.of(2026, 10, 7), measuredOg = 1.050), "Stout", 8)

        val lines = listOf(batchLine(packaged, today), batchLine(fermenting, today))

        lines shouldBe listOf("1 Sep · Pale Ale", "7 Oct · Stout •")
    }

    @Test
    fun `should write amounts without stray zeros or a dangling dot`() {
        val amounts = listOf(20.0, 4.25, 2.9999, 0.004, 0.1 + 0.2)

        val written = amounts.map { formatAmount(it) }

        written shouldBe listOf("20", "4.25", "3", "0.004", "0.3")
    }

    @Test
    fun `should shorten text without splitting an emoji`() {
        val text = "ab🍺cd"

        val short = text.shortened(4)

        short shouldBe "ab…"
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
        water: WaterSnapshot? = null,
    ) = Batch(
        id = 1, recipeId = 1, userId = 1, no = 1, brewDate = brewDate, packagedDate = packagedDate,
        measuredOg = measuredOg, measuredFg = measuredFg, measuredPostBoilVolumeL = measuredPostBoilVolumeL,
        notes = notes, abv = abv, mashEfficiency = mashEfficiency, createdAt = Instant.EPOCH, water = water,
    )
}
