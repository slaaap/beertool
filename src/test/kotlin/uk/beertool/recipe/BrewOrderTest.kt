package uk.beertool.recipe

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BrewOrderTest {

    @Test
    fun `should list fermentables in the order they go into the beer, malts first`() {
        val fermentables = listOf(
            RecipeFermentable("Priming sugar", 0.15, FermentableType.SUGAR, usage = FermentableUsage.PACKAGING),
            RecipeFermentable("Cherries", 3.0, FermentableType.FRUIT, usage = FermentableUsage.SECONDARY),
            RecipeFermentable("Candi sugar", 0.8, FermentableType.SUGAR, usage = FermentableUsage.BOIL),
            RecipeFermentable("Munich Malt", 1.0, FermentableType.MALT),
            RecipeFermentable("Pilsner Malt", 5.5, FermentableType.MALT),
        )

        val ordered = fermentables.inBrewOrder().map { it.name }

        ordered shouldBe listOf("Pilsner Malt", "Munich Malt", "Candi sugar", "Cherries", "Priming sugar")
    }

    @Test
    fun `should read a hop schedule as bittering charge down to dry hop`() {
        val hops = listOf(
            RecipeHop("Citra", 50.0, 12.0, HopUsage.DRY_HOP),
            RecipeHop("Saaz", 20.0, 3.5, HopUsage.BOIL, boilTimeMin = 10),
            RecipeHop("Magnum", 30.0, 12.0, HopUsage.BOIL, boilTimeMin = 60),
            RecipeHop("Mittelfrüh", 15.0, 4.0, HopUsage.FIRST_WORT, boilTimeMin = 90),
        )

        val ordered = hops.inBrewOrder().map { it.name }

        ordered shouldBe listOf("Mittelfrüh", "Magnum", "Saaz", "Citra")
    }

    @Test
    fun `should pitch the primary yeast before the conditioning one`() {
        val yeasts = listOf(
            RecipeYeast("CL23", 0.95, YeastUsage.PACKAGING),
            RecipeYeast("Wyeast 3787", 0.85, YeastUsage.PRIMARY),
        )

        val ordered = yeasts.inBrewOrder().map { it.name }

        ordered shouldBe listOf("Wyeast 3787", "CL23")
    }

    @Test
    fun `should order equal-stage lines by size, then by name, so the order never wobbles`() {
        val fermentables = listOf(
            RecipeFermentable("Wheat Malt", 1.0),
            RecipeFermentable("Carapils", 1.0),
            RecipeFermentable("Maris Otter", 4.0),
        )

        val ordered = fermentables.inBrewOrder().map { it.name }

        ordered shouldBe listOf("Maris Otter", "Carapils", "Wheat Malt")
    }
}
