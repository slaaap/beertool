package uk.beertool.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
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
import uk.beertool.brewing.WaterProfile
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.stats
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import uk.beertool.user.WaterSource
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
    fun `should refuse an efficiency or attenuation sent as a fraction`() {
        val tools = RecipeTools(newUser(), null)
        val draft = """{"name": "X", "efficiency": 0.72, "yeasts": [{"name": "US-05", "attenuation": 0.78}]}"""

        val error = shouldThrow<ToolError> { tools.estimateRecipe(args("recipe" to draft)) }

        error.message shouldContain "efficiency is a percentage (e.g. 72), not a fraction"
        error.message shouldContain "yeasts[0].attenuation is a percentage (e.g. 78), not a fraction"
    }

    @Test
    fun `should say which figure is out of range rather than failing inside`() {
        val tools = RecipeTools(newUser(), null)
        val draft = """{"name": "X", "hops": [{"name": "Citra", "amountG": 30, "alphaAcid": 130, "usage": "whirlpool"}]}"""

        val error = shouldThrow<ToolError> { tools.createRecipe(args("recipe" to draft)) }

        error.message shouldBe "Citra alpha acid must be between 0 and 50."
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

    @Test
    fun `should estimate the water and mash pH of a draft using the brewer's saved sources`() {
        val tap = WaterSource("London tap", WaterProfile(calcium = 99.0, magnesium = 4.5, sodium = 30.0, chloride = 45.0, sulphate = 47.0, bicarbonate = 250.0))
        val user = UserRepository.updatePreferences(newUser().id, BrewerPreferences(waterSources = listOf(tap)))!!
        val draft = paleAle.replace("\"yeasts\"", "\"water\": {\"source\": \"London tap\", \"gypsumG\": 4, \"acidMl\": 6}, \"yeasts\"")

        val water = RecipeTools(user, null).estimateRecipe(args("recipe" to draft)).jsonObject["water"]!!.jsonObject

        water["mgPerL"]!!.jsonObject["calcium"]!!.jsonPrimitive.double shouldBeGreaterThan 99.0
        water["mashPh"]!!.jsonPrimitive.double shouldBeLessThan 5.9
        water["acidMlForTarget"]!!.jsonPrimitive.double shouldBeGreaterThan 0.0
    }

    @Test
    fun `should name the saved sources when a draft asks for a water the brewer does not have`() {
        val tap = WaterSource("London tap", WaterProfile(calcium = 99.0))
        val user = UserRepository.updatePreferences(newUser().id, BrewerPreferences(waterSources = listOf(tap)))!!
        val draft = """{"name": "X", "water": {"source": "Burton"}}"""

        val error = shouldThrow<ToolError> { RecipeTools(user, null).estimateRecipe(args("recipe" to draft)) }

        error.message shouldContain "water.source \"Burton\" is not a saved water source; known sources: London tap"
    }

    @Test
    fun `should round-trip a recipe's water through get and update`() {
        val user = UserRepository.updatePreferences(newUser().id, BrewerPreferences(waterSources = listOf(WaterSource("Tap", WaterProfile(calcium = 99.0)))))!!
        val tools = RecipeTools(user, null)
        val draft = paleAle.replace("\"yeasts\"", "\"water\": {\"source\": \"Tap\", \"gypsumG\": 2.5, \"acidMl\": 3}, \"yeasts\"")
        val no = tools.createRecipe(args("recipe" to draft)).jsonObject["no"]!!.jsonPrimitive.int
        val fetched = tools.getRecipe(args("no" to "$no")).withoutNulls().jsonObject

        tools.updateRecipe(buildJsonObject { put("no", no); put("recipe", fetched["recipe"]!!) })

        RecipeRepository.findByNo(no, user.id)!!.water shouldBe RecipeWater(source = "Tap", gypsumG = 2.5, acidMl = 3.0)
    }

    @Test
    fun `should name the acids it knows when a draft asks for another`() {
        val user = UserRepository.updatePreferences(newUser().id, BrewerPreferences(waterSources = listOf(WaterSource("Tap", WaterProfile(calcium = 99.0)))))!!
        val draft = """{"name": "X", "water": {"source": "Tap", "acid": "citric", "acidMl": 3}}"""

        val error = shouldThrow<ToolError> { RecipeTools(user, null).estimateRecipe(args("recipe" to draft)) }

        error.message shouldContain "water.acid \"citric\" must be one of lactic80, phosphoric75, phosphoric10, ams"
    }

    @Test
    fun `should ask for a source when a draft's water names none`() {
        val user = UserRepository.updatePreferences(newUser().id, BrewerPreferences(waterSources = listOf(WaterSource("Tap", WaterProfile(calcium = 99.0)))))!!
        val draft = """{"name": "X", "water": {"acidMl": 3}}"""

        val error = shouldThrow<ToolError> { RecipeTools(user, null).estimateRecipe(args("recipe" to draft)) }

        error.message shouldContain "water.source is required; known sources: Tap"
    }

    private fun RecipeTools.draftFor(json: String) = draftArg(args("recipe" to json))

    private fun newUser(): User = UserRepository.create("mcp-${UUID.randomUUID()}@beertool.test", "hash", "Brewer")
}
