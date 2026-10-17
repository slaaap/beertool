package uk.beertool.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.stats
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.util.UUID

class RecipeToolsTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    private val paleAle = """
        {"name": "Agent Pale", "style": "American Pale Ale",
         "fermentables": [{"name": "Maris Otter", "amountKg": 4.5, "type": "malt"},
                          {"name": "Crystal 60", "amountKg": 0.3, "type": "malt", "colourEbc": 120}],
         "hops": [{"name": "Magnum", "amountG": 15, "alphaAcid": 12.5, "usage": "boil", "boilTimeMin": 60},
                  {"name": "Citra", "amountG": 50, "alphaAcid": 13, "usage": "whirlpool"}],
         "yeasts": [{"name": "SafAle US-05", "attenuation": 78}]}
    """.trimIndent()

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to Json.parseToJsonElement(v) })

    @Test
    fun `should estimate a draft with the same numbers the app shows once it is saved`() {
        val user = newUser()
        val tools = RecipeTools(user, null)

        val result = tools.estimateRecipe(args("recipe" to paleAle)).jsonObject

        val saved = RecipeRepository.create(user.id, tools.draftFor(paleAle)).stats(user.preferences.whirlpool)
        result["estimate"]!!.jsonObject["og"]!!.jsonPrimitive.double shouldBe Math.round(saved.og * 1000) / 1000.0
        result["estimate"]!!.jsonObject["ibu"]!!.jsonPrimitive.double shouldBe Math.round(saved.ibu * 10) / 10.0
        result["warnings"]!!.jsonArray.shouldBeEmpty()
    }

    @Test
    fun `should fit a draft to the brewer's kit when it leaves volumes and mash out`() {
        val prefs = BrewerPreferences(preBoilVolumeL = 30.0, boilOffL = 4.0, efficiency = 0.68, mashSteps = listOf(MashStep(65.0, 75)))
        val user = UserRepository.updatePreferences(newUser().id, prefs)!!

        val draft = RecipeTools(user, null).draftFor(paleAle)

        draft.preBoilVolumeL shouldBe 30.0
        draft.postBoilVolumeL shouldBe 26.0
        draft.efficiency shouldBe 0.68
        draft.mashSteps shouldBe listOf(MashStep(65.0, 75))
    }

    @Test
    fun `should name the allowed values when a draft uses an unknown one`() {
        val tools = RecipeTools(newUser(), null)
        val draft = """{"name": "X", "hops": [{"name": "Citra", "amountG": 30, "alphaAcid": 13, "usage": "late"}]}"""

        val error = shouldThrow<ToolError> { tools.estimateRecipe(args("recipe" to draft)) }

        error.message shouldContain "hops[0].usage \"late\" must be one of mash, first_wort, boil, whirlpool, dry_hop"
    }

    @Test
    fun `should refuse grain where it cannot go and a boil hop without a time`() {
        val tools = RecipeTools(newUser(), null)
        val draft = """
            {"name": "X", "fermentables": [{"name": "Pale", "amountKg": 4, "type": "malt", "usage": "boil"}],
             "hops": [{"name": "Magnum", "amountG": 15, "alphaAcid": 12, "usage": "boil"}]}
        """.trimIndent()

        val error = shouldThrow<ToolError> { tools.createRecipe(args("recipe" to draft)) }

        error.message shouldContain "a malt can only be used in mash"
        error.message shouldContain "hops[0].boilTimeMin is required for a boil hop"
    }

    @Test
    fun `should hand back a saved recipe in the shape it takes, so an edit round-trips unchanged`() {
        val user = newUser()
        val tools = RecipeTools(user, "https://beer.example")
        val no = tools.createRecipe(args("recipe" to paleAle)).jsonObject["no"]!!.jsonPrimitive.int
        val fetched = tools.getRecipe(args("no" to "$no")).withoutNulls().jsonObject

        val updated = tools.updateRecipe(buildJsonObject { put("no", no); put("recipe", fetched["recipe"]!!) }).jsonObject

        updated["estimate"] shouldBe fetched["estimate"]
        fetched["url"]!!.jsonPrimitive.content shouldBe "https://beer.example/recipes/$no"
    }

    @Test
    fun `should treat a null field as left out`() {
        val tools = RecipeTools(newUser(), null)
        val draft = """{"name": "X", "description": null, "efficiency": null,
            "hops": [{"name": "Magnum", "amountG": 15, "alphaAcid": 12, "usage": "boil", "boilTimeMin": 60, "dryHopDays": null}]}"""

        val parsed = tools.draftFor(draft)

        parsed.description shouldBe null
        parsed.efficiency shouldBe BrewerPreferences().efficiency
        parsed.hops.single().boilTimeMin shouldBe 60
    }

    @Test
    fun `should refuse a number field that holds text instead of quietly using a default`() {
        val tools = RecipeTools(newUser(), null)
        val draft = """{"name": "X", "efficiency": "abc", "mashSteps": [{"tempC": 66}], "yeasts": [{"name": "US-05", "attenuation": "high"}]}"""

        val error = shouldThrow<ToolError> { tools.estimateRecipe(args("recipe" to draft)) }

        error.message shouldContain "efficiency must be a number"
        error.message shouldContain "mashSteps[0].timeMin is required"
        error.message shouldContain "yeasts[0].attenuation must be a number"
    }

    @Test
    fun `should only ever see the token owner's recipes`() {
        val owner = newUser()
        RecipeTools(owner, null).createRecipe(args("recipe" to paleAle))
        val stranger = RecipeTools(newUser(), null)

        val found = stranger.searchRecipes(args()).jsonObject

        found["total"]!!.jsonPrimitive.int shouldBe 0
        shouldThrow<ToolError> { stranger.getRecipe(args("no" to "1")) }.message shouldContain "no recipe #1"
    }

    @Test
    fun `should warn when a style is not a BJCP name`() {
        val tools = RecipeTools(newUser(), null)
        val draft = paleAle.replace("American Pale Ale", "Hoppy Thing")

        val warnings = tools.estimateRecipe(args("recipe" to draft)).jsonObject["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }

        warnings shouldContain "Style \"Hoppy Thing\" is not a BJCP 2021 name; find_styles lists them."
    }

    private fun RecipeTools.draftFor(json: String) = draftArg(args("recipe" to json))

    private fun newUser(): User = UserRepository.create("mcp-${UUID.randomUUID()}@beertool.test", "hash", "Brewer")
}
