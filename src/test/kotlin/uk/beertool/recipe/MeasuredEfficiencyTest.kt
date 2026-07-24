package uk.beertool.recipe

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class MeasuredEfficiencyTest {

    @Test
    fun `should work out what the mash actually managed from the gravity it measured`() {

        val recipe = recipe(RecipeFermentable("Pale Malt", 5.0, extractPercent = 80.0))

        recipe.measuredEfficiency(1.048)!! shouldBe (0.625 plusOrMinus 0.001)
    }

    @Test
    fun `should give the mash no credit for sugar, which dissolves whole`() {
        val maltOnly = recipe(RecipeFermentable("Pale Malt", 5.0, extractPercent = 80.0))
        val sameMaltPlusSugar = recipe(
            RecipeFermentable("Pale Malt", 5.0, extractPercent = 80.0),
            RecipeFermentable(
                "Dextrose", 1.0, FermentableType.SUGAR,
                extractPercent = 100.0, usage = FermentableUsage.BOIL,
            ),
        )

        sameMaltPlusSugar.measuredEfficiency(1.048 + 0.0192)!! shouldBe
            (maltOnly.measuredEfficiency(1.048)!! plusOrMinus 0.001)
    }

    @Test
    fun `should measure efficiency in the volume actually reached, not only the planned one`() {
        val recipe = recipe(RecipeFermentable("Pale Malt", 5.0, extractPercent = 80.0))

        recipe.measuredEfficiency(1.048, postBoilVolumeL = 20.0)!! shouldBe (0.625 plusOrMinus 0.001)
        recipe.measuredEfficiency(1.048, postBoilVolumeL = 10.0)!! shouldBe (0.3125 plusOrMinus 0.001)
    }

    @Test
    fun `should report no efficiency for a wort with nothing mashed in it`() {
        val allSugar = recipe(
            RecipeFermentable("Dextrose", 2.0, FermentableType.SUGAR, usage = FermentableUsage.BOIL),
        )

        allSugar.measuredEfficiency(1.038).shouldBeNull()
    }

    private fun recipe(vararg fermentables: RecipeFermentable) = Recipe(
        id = 1, userId = 1, name = "Test", style = null, description = null,
        preBoilVolumeL = null, postBoilVolumeL = 20.0, fermenterVolumeL = 17.0, efficiency = 0.68, boilTimeMin = 60,
        mashSteps = emptyList(),
        fermentables = fermentables.toList(), hops = emptyList(), yeasts = emptyList(), extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}
