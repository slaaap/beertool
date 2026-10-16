package uk.beertool.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import uk.beertool.recipe.AmountUnit
import uk.beertool.recipe.Coded
import uk.beertool.recipe.ExtraUsage
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.YeastUsage

private fun prop(type: String, description: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("type", type)
    put("description", description)
    extra()
}

private fun string(description: String) = prop("string", description)
private fun number(description: String) = prop("number", description)
private fun integer(description: String) = prop("integer", description)

private fun codes(description: String, values: Collection<Coded>) = prop("string", description) {
    putJsonArray("enum") { values.forEach { add(JsonPrimitive(it.code)) } }
}

private fun objectOf(properties: Map<String, JsonObject>, required: List<String>, description: String? = null) = buildJsonObject {
    put("type", "object")
    description?.let { put("description", it) }
    put("properties", JsonObject(properties))
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

private fun arrayProp(description: String, item: JsonObject) = prop("array", description) { put("items", item) }

private val MASH_STEP = objectOf(mapOf("tempC" to number("Rest temperature, °C"), "timeMin" to integer("Rest length, minutes")), listOf("tempC", "timeMin"))

private val FERMENTABLE = objectOf(
    mapOf(
        "name" to string("e.g. Maris Otter, Crystal 60, Dextrose"),
        "amountKg" to number("Weight in kg"),
        "type" to codes("Kind of fermentable; decides where it may be used (malt and unmalted: mash only; extract: boil)", FermentableType.all),
        "usage" to codes("Brew stage it goes in; default mash", FermentableUsage.all),
        "boilTimeMin" to integer("Minutes before flame-out, for boil additions"),
        "colourEbc" to number("Colour in EBC (= 1.97 × SRM). Omit to use a typical value for the type"),
        "extractPercent" to number("Potential extract, % (fine grind as-is). Omit to use a typical value for the type"),
    ),
    listOf("name", "amountKg", "type"),
)

private val HOP = objectOf(
    mapOf(
        "name" to string("Variety, e.g. Citra"),
        "amountG" to number("Weight in grams"),
        "alphaAcid" to number("Alpha acid, %"),
        "usage" to codes("When it goes in; default boil", HopUsage.all),
        "boilTimeMin" to integer("Minutes before flame-out; required for boil and first_wort hops"),
        "dryHopDays" to integer("Contact time in days, for dry_hop"),
    ),
    listOf("name", "amountG", "alphaAcid", "usage"),
)

private val YEAST = objectOf(
    mapOf(
        "name" to string("Strain, e.g. SafAle US-05"),
        "attenuation" to number("Apparent attenuation, % (e.g. 78). Default 75"),
        "usage" to codes("Default primary", YeastUsage.all),
    ),
    listOf("name"),
)

private val EXTRA = objectOf(
    mapOf(
        "name" to string("e.g. Irish moss, Gypsum, Lactose"),
        "amount" to number("Quantity in the given unit"),
        "unit" to codes("Unit of amount; default g", AmountUnit.all),
        "usage" to codes("Brew stage; default boil", ExtraUsage.all),
        "boilTimeMin" to integer("Minutes before flame-out, for boil additions"),
    ),
    listOf("name", "amount"),
)

private val RECIPE = objectOf(
    mapOf(
        "name" to string("Recipe name"),
        "style" to string("BJCP 2021 style name exactly as find_styles returns it"),
        "description" to string("Free-text notes on the recipe"),
        "preBoilVolumeL" to number("Litres in the kettle before the boil. Default from the brewer profile"),
        "postBoilVolumeL" to number("Litres at flame-out. Default from the brewer profile"),
        "fermenterVolumeL" to number("Litres into the fermenter. Default from the brewer profile"),
        "efficiency" to number("Brewhouse efficiency, % (e.g. 72). Default from the brewer profile"),
        "boilTimeMin" to integer("Boil length, minutes. Default from the brewer profile"),
        "mashSteps" to arrayProp("Mash rests in order. Omit for the brewer's default mash; [] for none", MASH_STEP),
        "fermentables" to arrayProp("Grain bill and other fermentables", FERMENTABLE),
        "hops" to arrayProp("Hop additions", HOP),
        "yeasts" to arrayProp("Yeasts", YEAST),
        "extras" to arrayProp("Finings, salts, spices, adjuncts that add no sugar", EXTRA),
    ),
    listOf("name"),
    "A whole recipe. Units: kg, g, litres, °C, minutes; efficiency and attenuation as percentages.",
)

fun schema(properties: Map<String, JsonObject> = emptyMap(), required: List<String> = emptyList()) =
    ToolSchema(properties = JsonObject(properties), required = required)

val NO_ARGS = schema()

val SEARCH_ARGS = schema(
    mapOf(
        "query" to string("Words to match in recipe and ingredient names, e.g. \"citra pale\". Omit to list the latest recipes"),
        "limit" to integer("Most results to return, 1-50, default 20"),
    ),
)

val RECIPE_NO_ARGS = schema(mapOf("no" to integer("The recipe number, as shown in search results")), listOf("no"))

val BREWS_ARGS = schema(
    mapOf(
        "recipeNo" to integer("Only brews of this recipe"),
        "limit" to integer("Most results to return, 1-50, default 20"),
    ),
)

val RECIPE_ARGS = schema(mapOf("recipe" to RECIPE), listOf("recipe"))

val UPDATE_ARGS = schema(
    mapOf("no" to integer("The recipe number to replace"), "recipe" to RECIPE),
    listOf("no", "recipe"),
)

val STYLE_ARGS = schema(mapOf("query" to string("Part of a style name, e.g. \"IPA\" or \"stout\"")))
