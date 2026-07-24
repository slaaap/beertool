package uk.beertool.web

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.dom.createHTMLDocument
import kotlinx.html.dom.serialize
import kotlinx.html.html
import org.junit.jupiter.api.Test
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
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

    private fun render(recipe: Recipe): String {
        val user = User(id = 1, email = "b@t.uk", displayName = "Brewer", createdAt = Instant.EPOCH)
        return createHTMLDocument()
            .html { recipeViewPage(user, recipe, recipe.stats(), brews = emptyList(), activeBrewDay = null) }
            .serialize()
    }

    private fun recipe(mashSteps: List<MashStep>) = Recipe(
        id = 1, userId = 1, name = "Test", style = "Saison", description = null,
        preBoilVolumeL = 27.0, postBoilVolumeL = 22.0, fermenterVolumeL = 19.0,
        efficiency = 0.68, boilTimeMin = 60, mashSteps = mashSteps,
        fermentables = listOf(RecipeFermentable(name = "Pilsner", amountKg = 5.0)),
        hops = emptyList(),
        yeasts = listOf(RecipeYeast(name = "US-05", attenuation = 0.80)),
        extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}
