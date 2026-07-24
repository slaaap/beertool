package uk.beertool.recipe

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class BrewDayScheduleTest {

    @Test
    fun `should place each hop by its boil time, longest first, whirlpool at flame-out`() {
        val recipe = recipe(
            RecipeHop("Magnum", 20.0, 12.0, usage = HopUsage.FIRST_WORT),
            RecipeHop("Cascade", 30.0, 6.0, usage = HopUsage.BOIL, boilTimeMin = 15),
            RecipeHop("Citra", 40.0, 12.0, usage = HopUsage.WHIRLPOOL, boilTimeMin = 20),
            RecipeHop("Simcoe", 25.0, 13.0, usage = HopUsage.DRY_HOP),
        )

        recipe.boilAdditions() shouldBe listOf(
            BoilAddition(60, "Magnum 20 g"),
            BoilAddition(15, "Cascade 30 g"),
            BoilAddition(0, "Citra 40 g"),

        )
    }

    @Test
    fun `should not let a hop outlast the boil`() {
        val recipe = recipe(RecipeHop("Target", 30.0, 11.0, usage = HopUsage.BOIL, boilTimeMin = 90))

        recipe.boilAdditions() shouldBe listOf(BoilAddition(60, "Target 30 g"))
    }

    private fun recipe(vararg hops: RecipeHop) = Recipe(
        id = 1, userId = 1, name = "Test", style = null, description = null,
        preBoilVolumeL = null, postBoilVolumeL = 22.0, fermenterVolumeL = 19.0, efficiency = 0.75, boilTimeMin = 60,
        mashSteps = emptyList(), fermentables = emptyList(), hops = hops.toList(),
        yeasts = emptyList(), extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}
