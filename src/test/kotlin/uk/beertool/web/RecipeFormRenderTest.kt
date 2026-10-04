package uk.beertool.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.html.dom.createHTMLDocument
import kotlinx.html.dom.serialize
import kotlinx.html.html
import org.junit.jupiter.api.Test
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.estimatedStats
import uk.beertool.user.User
import java.time.Instant

class RecipeFormRenderTest {

    @Test
    fun `should give every brew stage its own table, each supplying the usage of its rows`() {
        val html = render(null)

        HopUsage.all.forEach { usage -> html shouldContain """id="hop-${usage.code}-body"""" }
        html shouldContain """value="whirlpool""""
    }

    @Test
    fun `should show only the stages a recipe actually uses`() {
        val html = render(recipe(hop(HopUsage.BOIL, boilTimeMin = 60)))

        html.stageIsHidden("hop-boil") shouldBe false
        html.stageIsHidden("hop-whirlpool") shouldBe true
        html.stageIsHidden("hop-dry_hop") shouldBe true
    }

    @Test
    fun `should number hop rows across the stage tables, since they post as one list`() {
        val html = render(
            recipe(
                hop(HopUsage.BOIL, name = "Magnum", boilTimeMin = 60),
                hop(HopUsage.WHIRLPOOL, name = "Nelson", boilTimeMin = 10),
                hop(HopUsage.DRY_HOP, name = "Citra", dryHopDays = 4),
            ),
        )

        html.shouldHaveField("hops.0.name", "Magnum")
        html.shouldHaveField("hops.1.name", "Nelson")
        html.shouldHaveField("hops.2.name", "Citra")
        html.shouldHaveField("hops.2.dryHopDays", "4")
    }

    @Test
    fun `should label the whirlpool stage with the temperature the brewer adds at`() {
        val html = render(null)

        html shouldContain "hops in at 80"
    }

    private fun String.stageIsHidden(id: String) =
        Regex("""<div class="hop-stage"([^>]*)id="$id"""").find(this)!!.groupValues[1].contains("hidden")

    private fun String.shouldHaveField(name: String, value: String) =
        this shouldContain Regex("""name="${Regex.escape(name)}"[^>]*value="$value"""")

    private fun render(recipe: Recipe?): String {
        val user = User(id = 1, email = "b@t.uk", displayName = "Brewer", createdAt = Instant.EPOCH)
        return createHTMLDocument()
            .html { recipeFormPage(user, recipe, NewRecipe(name = "x").estimatedStats()) }
            .serialize()
    }

    private fun hop(
        usage: HopUsage,
        name: String = "Hop",
        boilTimeMin: Int? = null,
        dryHopDays: Int? = null,
    ) = RecipeHop(
        name = name, amountG = 30.0, alphaAcid = 12.0,
        usage = usage, boilTimeMin = boilTimeMin, dryHopDays = dryHopDays,
    )

    private fun recipe(vararg hops: RecipeHop) = Recipe(
        id = 1, userId = 1, name = "Test", style = null, description = null,
        preBoilVolumeL = 27.0, postBoilVolumeL = 22.0, fermenterVolumeL = 19.0,
        efficiency = 0.75, boilTimeMin = 60, mashSteps = emptyList(),
        fermentables = listOf(RecipeFermentable(name = "Pilsner", amountKg = 5.0)),
        hops = hops.toList(), yeasts = emptyList(), extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}
