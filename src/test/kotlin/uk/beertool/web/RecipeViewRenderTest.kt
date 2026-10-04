package uk.beertool.web

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.dom.createHTMLDocument
import kotlinx.html.dom.serialize
import kotlinx.html.html
import org.junit.jupiter.api.Test
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.RecipeYeast
import uk.beertool.recipe.stats
import uk.beertool.user.User
import java.time.Instant

class RecipeViewRenderTest {

    @Test
    fun `should render a recipe whose mash schedule moves the attenuation`() {
        val html = render(recipe(mashSteps = listOf(MashStep(63.0, 45), MashStep(72.0, 20))))

        html shouldContain "Brew day"
        html shouldContain "Estimates"
        html shouldContain """<table class="bill mash">"""
        html shouldContain "Rest"
    }

    @Test
    fun `should show no mash table at all when the mash was not recorded`() {
        val html = render(recipe(mashSteps = emptyList()))

        html shouldNotContain """<table class="bill mash">"""
    }

    @Test
    fun `should group the hop schedule by brew stage, naming the time each stage measures`() {
        val html = render(
            recipe(
                mashSteps = emptyList(),
                hops = listOf(
                    RecipeHop("Magnum", 10.0, 12.0, HopUsage.BOIL, boilTimeMin = 30),
                    RecipeHop("Nelson", 33.0, 12.5, HopUsage.WHIRLPOOL, boilTimeMin = 10),
                    RecipeHop("Citra", 67.0, 12.0, HopUsage.DRY_HOP, dryHopDays = 4),
                ),
            ),
        )

        html shouldContain "Stand (min)"
        html shouldContain "Days"
        html.hopTables().forEach { it shouldNotContain "Use" }
    }

    @Test
    fun `should leave out a stage the recipe never uses`() {
        val html = render(
            recipe(
                mashSteps = emptyList(),
                hops = listOf(RecipeHop("Magnum", 10.0, 12.0, HopUsage.BOIL, boilTimeMin = 30)),
            ),
        )

        html shouldContain "Boil"
        html shouldNotContain "Dry hop"
    }

    private fun String.hopTables() =
        Regex("""<table class="bill hops">.*?</table>""", RegexOption.DOT_MATCHES_ALL).findAll(this)
            .map { it.value }
            .toList()

    private fun render(recipe: Recipe): String {
        val user = User(id = 1, email = "b@t.uk", displayName = "Brewer", createdAt = Instant.EPOCH)
        return createHTMLDocument()
            .html { recipeViewPage(user, recipe, recipe.stats(), brews = emptyList(), activeBrewDay = null) }
            .serialize()
    }

    private fun recipe(mashSteps: List<MashStep>, hops: List<RecipeHop> = emptyList()) = Recipe(
        id = 1, userId = 1, name = "Test", style = "Saison", description = null,
        preBoilVolumeL = 27.0, postBoilVolumeL = 22.0, fermenterVolumeL = 19.0,
        efficiency = 0.68, boilTimeMin = 60, mashSteps = mashSteps,
        fermentables = listOf(RecipeFermentable(name = "Pilsner", amountKg = 5.0)),
        hops = hops,
        yeasts = listOf(RecipeYeast(name = "US-05", attenuation = 0.80)),
        extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}
