package uk.beertool.brewhouse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.common.fmt
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.mashWater
import uk.beertool.user.BrewerPreferences
import java.time.Instant

class BrewhouseScheduleTest {

    private val prefs = BrewerPreferences(mashThicknessLPerKg = 3.0, grainAbsorptionLPerKg = 0.8)

    @Test
    fun `should send the strike volume and temperature the Brew Day mash-water guide shows`() {
        val recipe = recipe()

        val schedule = brewhouseSchedule(7, recipe, prefs)

        val guide = recipe.mashWater(prefs.mashThicknessLPerKg, prefs.grainAbsorptionLPerKg)!!
        fmt(schedule.waterL, 1) shouldBe fmt(guide.strikeVolumeL, 1)
        fmt(schedule.strikeC, 1) shouldBe fmt(guide.strikeTempC, 1)
    }

    @Test
    fun `should count only the fermentables that go into the mash as grain`() {
        val recipe = recipe(
            fermentables = listOf(
                RecipeFermentable("Maris Otter", 4.2, FermentableType.MALT, usage = FermentableUsage.MASH),
                RecipeFermentable("Flaked oats", 0.5, FermentableType.UNMALTED, usage = FermentableUsage.MASH),
                RecipeFermentable("Light DME", 1.0, FermentableType.EXTRACT, usage = FermentableUsage.BOIL),
                RecipeFermentable("Dextrose", 0.3, FermentableType.SUGAR, usage = FermentableUsage.WHIRLPOOL),
                RecipeFermentable("Raspberries", 1.5, FermentableType.FRUIT, usage = FermentableUsage.SECONDARY),
            ),
        )

        val schedule = brewhouseSchedule(7, recipe, prefs)

        schedule.grainKg shouldBe 4.7
        schedule.waterL shouldBe 14.1
    }

    @Test
    fun `should carry the mash steps, boil length and pre-boil volume`() {
        val recipe = recipe(steps = listOf(MashStep(52.0, 15), MashStep(66.0, 60), MashStep(76.0, 10)))

        val schedule = brewhouseSchedule(7, recipe, prefs)

        schedule.batchId shouldBe 7
        schedule.recipeName shouldBe "Pale"
        schedule.steps shouldBe listOf(ScheduleStep(52.0, 15), ScheduleStep(66.0, 60), ScheduleStep(76.0, 10))
        schedule.boil shouldBe BoilPlan(60, 27.0)
    }

    @Test
    fun `should leave out the boil when the recipe has none`() {
        val recipe = recipe(boilTimeMin = 0)

        val schedule = brewhouseSchedule(7, recipe, prefs)

        schedule.boil.shouldBeNull()
    }

    @Test
    fun `should refuse a recipe without mash steps as unprocessable`() {
        val recipe = recipe(steps = emptyList())

        val problem = shouldThrow<BrewhouseProblem> { brewhouseSchedule(7, recipe, prefs) }

        problem.status shouldBe 422
    }

    @Test
    fun `should refuse a mash step the controller would not accept`() {
        val recipe = recipe(steps = listOf(MashStep(95.0, 30)))

        val problem = shouldThrow<BrewhouseProblem> { brewhouseSchedule(7, recipe, prefs) }

        problem.status shouldBe 422
    }

    @Test
    fun `should refuse a recipe with no pre-boil volume, as strike water cannot be worked out`() {
        val recipe = recipe(preBoilVolumeL = null)

        val problem = shouldThrow<BrewhouseProblem> { brewhouseSchedule(7, recipe, prefs) }

        problem.status shouldBe 422
    }

    private fun recipe(
        steps: List<MashStep> = listOf(MashStep(66.0, 60)),
        fermentables: List<RecipeFermentable> = listOf(RecipeFermentable("Maris Otter", 4.5, FermentableType.MALT)),
        boilTimeMin: Int = 60,
        preBoilVolumeL: Double? = 27.0,
    ) = Recipe(
        id = 1, userId = 1, name = "Pale", style = null, description = null,
        preBoilVolumeL = preBoilVolumeL, postBoilVolumeL = 23.0, fermenterVolumeL = 21.0, efficiency = 0.75, boilTimeMin = boilTimeMin,
        mashSteps = steps, fermentables = fermentables, hops = emptyList(),
        yeasts = emptyList(), extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}
