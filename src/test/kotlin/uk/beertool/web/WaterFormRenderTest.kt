package uk.beertool.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import org.junit.jupiter.api.Test
import uk.beertool.brewing.WaterProfile
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.estimatedStats
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.User
import uk.beertool.user.WaterSource
import java.time.Instant

class WaterFormRenderTest {

    private val waters = listOf(
        WaterSource("London tap", WaterProfile(calcium = 99.0, magnesium = 4.5, sodium = 30.0, chloride = 45.0, sulphate = 47.0, bicarbonate = 250.0)),
        WaterSource("Tesco", WaterProfile(calcium = 17.0, magnesium = 5.0, sodium = 10.0, chloride = 13.0, sulphate = 14.0, bicarbonate = 46.0)),
    )

    @Test
    fun `should render a recipe's water with its mix and only the additions it uses`() {
        val water = RecipeWater(source = "London tap", blendSource = "Tesco", blendPercent = 40.0, gypsumG = 4.0, lacticMl = 3.0)

        val html = render(recipe(water))

        html.fieldIsHidden("gypsumG") shouldBe false
        html.fieldIsHidden("lacticMl") shouldBe false
        html.fieldIsHidden("epsomG") shouldBe true
        html.tag("input", "water.blendPercent") shouldContain """value="40""""
        html.tag("input", "water.blendPercent") shouldNotContain "disabled"
    }

    @Test
    fun `should keep a recipe without water closed, posting nothing until the brewer adds it`() {
        val html = render(recipe(water = null))

        html shouldContain """<div class="water-add">"""
        html shouldContain """<div class="water-panel" hidden"""
        html.tag("select", "water.source") shouldContain "disabled"
    }

    @Test
    fun `should send a brewer without saved waters to Settings instead of offering water`() {
        val html = render(recipe(water = null), sources = emptyList())

        html shouldContain "Add water sources in Settings"
        html shouldNotContain "name=\"water.source\""
    }

    @Test
    fun `should offer to fill in the lactic acid the mash needs`() {
        val html = render(recipe(RecipeWater(source = "London tap")))

        html shouldContain Regex("""onclick="waterUseLactic\('\d+\.\d'\)"""")
    }

    private fun String.fieldIsHidden(field: String) =
        Regex("""<div class="field"[^>]*data-add="$field"[^>]*>""").find(this)!!.value.contains("hidden")

    private fun String.tag(element: String, name: String) =
        Regex("""<$element[^>]*name="${Regex.escape(name)}"[^>]*>""").find(this)!!.value

    private fun render(recipe: Recipe?, sources: List<WaterSource> = waters): String {
        val user = User(id = 1, email = "b@t.uk", displayName = "Brewer", createdAt = Instant.EPOCH, preferences = BrewerPreferences(waterSources = sources))
        return createHTML().html { recipeFormPage(user, recipe, NewRecipe(name = "x").estimatedStats()) }
    }

    private fun recipe(water: RecipeWater?) = Recipe(
        id = 1, userId = 1, name = "Pale", style = null, description = null,
        preBoilVolumeL = 27.0, postBoilVolumeL = 22.0, fermenterVolumeL = 19.0,
        efficiency = 0.75, boilTimeMin = 60, mashSteps = emptyList(),
        fermentables = listOf(RecipeFermentable(name = "Maris Otter", amountKg = 5.0, colourEbc = 6.0)),
        hops = emptyList(), yeasts = emptyList(), extras = emptyList(), water = water,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}
