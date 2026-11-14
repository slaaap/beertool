package uk.beertool.telegram

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import uk.beertool.batch.Batch
import uk.beertool.brewing.WaterProfile
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.RecipeYeast
import uk.beertool.recipe.WaterSnapshot
import uk.beertool.recipe.stats
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.WaterSource
import java.time.Instant
import java.time.LocalDate

class BrewPanelTest {

    private val now = Instant.parse("2026-10-16T10:00:00Z")
    private val today = LocalDate.of(2026, 10, 16)

    @Test
    fun `should show only the stage at hand and where it sits in the day`() {
        val panel = panel(session(BrewDayStage.MASH))

        panel.text shouldContain "Water › <b>Mash</b> › Boil › Chill › Ferment"
        panel.text shouldNotContain "Strike"
        panel.text shouldNotContain "min boil"
    }

    @Test
    fun `should split the water treatment between the strike and sparge water`() {
        val tap = WaterSource("London tap", WaterProfile(calcium = 99.0, bicarbonate = 250.0))
        val water = WaterSnapshot(RecipeWater(source = "London tap", gypsumG = 3.5, acidMl = 4.0), listOf(tap))

        val panel = panel(session(BrewDayStage.WATER), batch(water = water))

        panel.text shouldContain "<b>Strike</b> 13.5 L at"
        panel.text shouldContain "• Gypsum 1.5 g\n• Lactic acid 80% 4.0 ml, into the mash\n<b>Sparge</b>"
        panel.text shouldContain "<b>Sparge</b> 18.0 L\n• Gypsum 2.0 g"
        panel.text shouldContain "<i>London tap · mash pH about"
    }

    @Test
    fun `should leave water treatment off a brew that has none`() {
        val panel = panel(session(BrewDayStage.WATER))

        panel.text shouldNotContain "mash pH"
    }

    @Test
    fun `should offer to start the next mash step once the mash is at temperature`() {
        val panel = panel(session(BrewDayStage.MASH, mashStep = 1))

        panel.text shouldContain "✓ 44 °C · 15 min\n▸ <b>66 °C · 40 min</b>\n◦ 72 °C · 10 min"
        panel.buttons() shouldContain "▶ At 66 °C · start 40 min"
    }

    @Test
    fun `should count down a running mash step`() {
        val timer = BrewTimer(TimerKind.MASH, endsAt = now.plusSeconds(20 * 60))

        val panel = panel(session(BrewDayStage.MASH, timer = timer))

        panel.text shouldContain "⏱ <b>44 °C · 15 min</b>"
        panel.text shouldContain "░░░░░░░░░░ 20 min left"
        panel.buttons() shouldContain "⏭ End step now"
    }

    @Test
    fun `should send the brewer to sparge and log the pre-boil volume when the mash is done`() {
        val panel = panel(session(BrewDayStage.MASH, mashStep = 3))

        panel.text shouldContain "✅ Mash done. Sparge with 18.0 L."
        panel.buttons() shouldContain "📏 Pre-boil"
    }

    @Test
    fun `should tick off boil additions already called`() {
        val timer = BrewTimer(TimerKind.BOIL, endsAt = now.plusSeconds(30 * 60), announcedDownTo = 60)

        val panel = panel(session(BrewDayStage.BOIL, timer = timer))

        panel.text shouldContain "▓▓▓▓▓░░░░░ 30 min to flame-out"
        panel.text shouldContain "✓ 60 min · Magnum 20 g"
        panel.text shouldContain "◦ 15 min · Cascade 30 g"
        panel.buttons() shouldContain "⏹ Stop boil timer"
    }

    @Test
    fun `should keep a running timer in sight from another stage`() {
        val timer = BrewTimer(TimerKind.BOIL, endsAt = now.plusSeconds(30 * 60), announcedDownTo = 60)

        val panel = panel(session(BrewDayStage.CHILL, timer = timer))

        panel.text shouldContain "⏱ Boil · 30 min to flame-out"
    }

    @Test
    fun `should ask for the post-chill readings and name the yeast to pitch`() {
        val panel = panel(session(BrewDayStage.CHILL), batch(measuredOg = 1.052))

        panel.text shouldContain "• US-05"
        panel.text shouldContain "Post-boil <i>to log</i> · plan 23 L"
        panel.buttons() shouldContain "✓ OG"
        panel.buttons() shouldContain "📏 Fermenter"
    }

    @Test
    fun `should offer packaging only once the final gravity is in`() {
        val waiting = panel(session(BrewDayStage.FERMENT), batch(measuredOg = 1.052))
        val done = panel(session(BrewDayStage.FERMENT), batch(measuredOg = 1.052, measuredFg = 1.010))

        waiting.buttons() shouldNotContain "🍺 Mark packaged"
        done.buttons() shouldContain "🍺 Mark packaged"
    }

    @Test
    fun `should offer to cancel a brew only until its OG is in`() {
        val brewing = panel(session(BrewDayStage.BOIL))
        val fermenting = panel(session(BrewDayStage.FERMENT), batch(measuredOg = 1.052))

        brewing.buttons() shouldContain "✖ Cancel brew"
        fermenting.buttons() shouldNotContain "✖ Cancel brew"
    }

    @Test
    fun `should step between stages from the panel`() {
        val panel = panel(session(BrewDayStage.BOIL))

        panel.keyboard.inlineKeyboard.flatten().map { it.callbackData } shouldContain "st:7:1:MASH"
        panel.keyboard.inlineKeyboard.flatten().map { it.callbackData } shouldContain "st:7:1:CHILL"
    }

    @Test
    fun `should put a saved reading at the top of the panel`() {
        val panel = brewPanel(recipe(), batch(), session(BrewDayStage.BOIL), BrewerPreferences(), now, today, flash = "✅ Saved pre-boil 27 L")

        panel.text.lines().first() shouldBe "✅ Saved pre-boil 27 L"
    }

    @Test
    fun `should ask for a reading with the plan as the hint`() {
        val recipe = recipe()

        val prompt = readingPrompt("og", recipe, batch(), recipe.stats())!!

        prompt.text shouldContain "<b>Original gravity?</b>"
        prompt.placeholder shouldBe prompt.text.substringAfter("Plan ").substringBefore("</i>")
    }

    @Test
    fun `should tell the brewer the next rest when a mash step ends`() {
        val text = mashStepDoneText(recipe(), step = 0, spargeL = 10.1)

        text shouldContain "<b>Step 1 done</b>"
        text shouldContain "Raise the mash to <b>66 °C</b>"
    }

    @Test
    fun `should send the brewer to sparge when the last mash step ends`() {
        val text = mashStepDoneText(recipe(), step = 2, spargeL = 10.1)

        text shouldContain "<b>Mash done</b>"
        text shouldContain "Sparge with 10.1 L"
    }

    private fun BrewPanel.buttons() = keyboard.inlineKeyboard.flatten().map { it.text }

    private fun panel(session: BrewSession, batch: Batch = batch()) =
        brewPanel(recipe(), batch, session, BrewerPreferences(), now, today)

    private fun session(stage: BrewDayStage, mashStep: Int = 0, timer: BrewTimer? = null) = BrewSession(
        batchId = 1, userId = 1, chatId = 1, panelMessageId = 5, stage = stage, mashStep = mashStep, timer = timer, panelRefreshedAt = null,
    )

    private fun recipe() = Recipe(
        id = 1, userId = 1, no = 7, name = "Pale Ale", style = "American IPA", description = null,
        preBoilVolumeL = 27.0, postBoilVolumeL = 23.0, fermenterVolumeL = 21.0, efficiency = 0.72, boilTimeMin = 60,
        mashSteps = listOf(MashStep(44.0, 15), MashStep(66.0, 40), MashStep(72.0, 10)),
        fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5)),
        hops = listOf(
            RecipeHop(name = "Magnum", amountG = 20.0, alphaAcid = 12.0, usage = HopUsage.BOIL, boilTimeMin = 60),
            RecipeHop(name = "Cascade", amountG = 30.0, alphaAcid = 6.0, usage = HopUsage.BOIL, boilTimeMin = 15),
        ),
        yeasts = listOf(RecipeYeast(name = "US-05")), extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun batch(measuredOg: Double? = null, measuredFg: Double? = null, water: WaterSnapshot? = null) = Batch(
        id = 1, recipeId = 1, userId = 1, no = 1, brewDate = today, packagedDate = null,
        measuredOg = measuredOg, measuredFg = measuredFg, abv = null, mashEfficiency = null, createdAt = Instant.EPOCH, water = water,
    )
}
