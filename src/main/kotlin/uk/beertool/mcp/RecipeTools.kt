package uk.beertool.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import uk.beertool.batch.Batch
import uk.beertool.batch.BatchRepository
import uk.beertool.brewing.Acid
import uk.beertool.brewing.WaterProfile
import uk.beertool.common.asPercent
import uk.beertool.common.fmt
import uk.beertool.common.roundTo
import uk.beertool.recipe.AmountUnit
import uk.beertool.recipe.CodeLookup
import uk.beertool.recipe.Coded
import uk.beertool.recipe.ExtraUsage
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.FlavourBalance
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.MAX_MASH_PH
import uk.beertool.recipe.MAX_UNMALTED_FRACTION
import uk.beertool.recipe.MIN_MASH_PH
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.TARGET_MASH_PH
import uk.beertool.recipe.WaterReport
import uk.beertool.recipe.YeastUsage
import uk.beertool.recipe.estimatedStats
import uk.beertool.recipe.inBrewOrder
import uk.beertool.recipe.stats
import uk.beertool.recipe.unmaltedFraction
import uk.beertool.recipe.waterReport
import uk.beertool.user.User
import uk.beertool.web.BeerStyles
import uk.beertool.web.RecipeForm
import uk.beertool.web.toNewRecipe

class ToolError(message: String) : RuntimeException(message)

// Lenient so JSON numbers feed the web form's string-based field serializers; nulls mean "use the default".
private val ToolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

// Absent beats null for an agent reading the result: less noise, and nothing to echo back.
fun JsonElement.withoutNulls(): JsonElement = when (this) {
    is JsonObject -> JsonObject(filterValues { it !is JsonNull }.mapValues { it.value.withoutNulls() })
    is JsonArray -> JsonArray(map { it.withoutNulls() })
    else -> this
}

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 50

class RecipeTools(private val user: User, private val publicUrl: String?) {

    private val prefs get() = user.preferences
    private val whirlpool get() = prefs.whirlpool

    fun brewerProfile(): JsonElement = buildJsonObject {
        put("preBoilVolumeL", prefs.preBoilVolumeL)
        put("postBoilVolumeL", prefs.postBoilVolumeL)
        put("fermenterVolumeL", prefs.fermenterVolumeL)
        put("boilOffL", prefs.boilOffL)
        put("kettleRetentionL", prefs.kettleRetentionL)
        put("efficiencyPercent", prefs.efficiency.asPercent().roundTo(1))
        put("boilTimeMin", prefs.boilTimeMin)
        put("whirlpoolTempC", prefs.whirlpoolTempC)
        put("coolingTimeMin", prefs.coolingTimeMin)
        put("mashThicknessLPerKg", prefs.mashThicknessLPerKg)
        put("grainAbsorptionLPerKg", prefs.grainAbsorptionLPerKg)
        putJsonArray("defaultMashSteps") { prefs.mashSteps.forEach { add(mashStep(it.tempC, it.timeMin)) } }
        putJsonArray("waterSources") {
            prefs.waterSources.forEach { add(buildJsonObject { put("name", it.name); put("mgPerL", ions(it.profile)) }) }
        }
    }

    fun searchRecipes(args: JsonObject): JsonElement {
        val query = args.string("query")
        val limit = args.limit()
        val found = if (query.isNullOrBlank()) RecipeRepository.listByUser(user.id, whirlpool)
        else RecipeRepository.search(user.id, query, whirlpool)
        val brews = BatchRepository.brewInfoByRecipe(user.id)
        return buildJsonObject {
            put("total", found.size)
            putJsonArray("recipes") {
                found.take(limit).forEach { r ->
                    add(buildJsonObject {
                        put("no", r.no)
                        put("name", r.name)
                        put("style", r.style)
                        put("estimate", estimate(r.stats))
                        put("timesBrewed", brews[r.id]?.count ?: 0)
                        put("lastBrewed", brews[r.id]?.lastBrewed?.toString())
                    })
                }
            }
        }
    }

    fun getRecipe(args: JsonObject): JsonElement {
        val recipe = recipeArg(args)
        return buildJsonObject {
            put("no", recipe.no)
            put("url", recipeUrl(recipe.no))
            put("recipe", recipeJson(recipe))
            put("estimate", estimate(recipe.stats(whirlpool)))
            recipe.waterReport(prefs)?.let { put("water", waterJson(it)) }
            putJsonArray("brews") { BatchRepository.listByRecipe(recipe.id, user.id).forEach { add(brewJson(recipe, it)) } }
        }
    }

    fun listBrews(args: JsonObject): JsonElement {
        val recipeNo = args.int("recipeNo")
        val limit = args.limit()
        val summaries = BatchRepository.listByUser(user.id).filter { recipeNo == null || it.recipeNo == recipeNo }
        val recipes = summaries.map { it.batch.recipeId }.distinct().mapNotNull { RecipeRepository.findById(it, user.id) }.associateBy { it.id }
        return buildJsonObject {
            put("total", summaries.size)
            putJsonArray("brews") {
                summaries.take(limit).forEach { s -> recipes[s.batch.recipeId]?.let { add(brewJson(it, s.batch)) } }
            }
        }
    }

    fun estimateRecipe(args: JsonObject): JsonElement {
        val draft = draftArg(args)
        val water = draft.waterReport(prefs)
        return buildJsonObject {
            put("estimate", estimate(draft.estimatedStats(whirlpool)))
            water?.let { put("water", waterJson(it)) }
            putJsonArray("warnings") { (warnings(draft) + water?.warnings.orEmpty()).forEach { add(JsonPrimitive(it)) } }
        }
    }

    fun createRecipe(args: JsonObject): JsonElement {
        val created = RecipeRepository.create(user.id, draftArg(args))
        return saved(created)
    }

    fun updateRecipe(args: JsonObject): JsonElement {
        val existing = recipeArg(args)
        val updated = RecipeRepository.update(existing.id, user.id, draftArg(args)) ?: throw ToolError("Recipe #${existing.no} could not be updated.")
        return saved(updated)
    }

    fun findStyles(args: JsonObject): JsonElement {
        val query = args.string("query").orEmpty().trim()
        val matches = BeerStyles.all.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        return buildJsonObject {
            putJsonArray("styles") {
                matches.take(MAX_LIMIT).forEach { s -> add(buildJsonObject { put("name", s.name); put("url", s.url) }) }
            }
        }
    }

    private fun saved(recipe: Recipe) = buildJsonObject {
        put("no", recipe.no)
        put("url", recipeUrl(recipe.no))
        put("estimate", estimate(recipe.stats(whirlpool)))
    }

    private fun recipeArg(args: JsonObject): Recipe {
        val no = args.int("no") ?: throw ToolError("Give the recipe number as \"no\".")
        return RecipeRepository.findByNo(no, user.id) ?: throw ToolError("There is no recipe #$no.")
    }

    internal fun draftArg(args: JsonObject): NewRecipe {
        val json = args["recipe"] as? JsonObject ?: throw ToolError("Give the recipe as a \"recipe\" object.")
        validate(json)
        validateWater(json["water"] as? JsonObject)
        val form = ToolJson.decodeFromJsonElement(RecipeForm.serializer(), json)
        val draft = form.toNewRecipe(prefs)
        return draft.copy(
            preBoilVolumeL = draft.preBoilVolumeL ?: prefs.preBoilVolumeL,
            mashSteps = if ("mashSteps" in json) draft.mashSteps else prefs.mashSteps,
        )
    }

    private fun validateWater(water: JsonObject?) {
        if (water == null) return
        val known = prefs.waterSources.map { it.name }
        if (water.string("source").isNullOrBlank()) {
            throw ToolError(if (known.isEmpty()) "water needs a source, but the brewer has no saved water sources; leave water out" else "water.source is required; known sources: ${known.joinToString()}")
        }
        val unknown = listOf("source", "blendSource").mapNotNull { key -> water.string(key)?.takeIf { it.isNotBlank() && it !in known }?.let { key to it } }
        if (unknown.isEmpty()) return
        val choices = if (known.isEmpty()) "the brewer has no saved water sources" else "known sources: ${known.joinToString()}"
        throw ToolError(unknown.joinToString("\n") { (key, name) -> "water.$key \"$name\" is not a saved water source; $choices" })
    }

    private fun warnings(draft: NewRecipe) = buildList {
        if (draft.fermentables.isEmpty()) add("There are no fermentables, so the gravity estimate is meaningless.")
        if (draft.yeasts.isEmpty()) add("No yeast: the estimate assumes 75% attenuation.")
        if (draft.unmaltedFraction() > MAX_UNMALTED_FRACTION) {
            add("Unmalted grain is ${fmt(draft.unmaltedFraction().asPercent(), 0)}% of the grist; above ${fmt(MAX_UNMALTED_FRACTION.asPercent(), 0)}% the mash may lack enzymes to convert it.")
        }
        if (draft.style != null && BeerStyles.urlFor(draft.style) == null) add("Style \"${draft.style}\" is not a BJCP 2021 name; find_styles lists them.")
    }

    private fun recipeUrl(no: Int) = publicUrl?.let { "$it/recipes/$no" }

    private fun recipeJson(r: Recipe) = buildJsonObject {
        put("name", r.name)
        put("style", r.style)
        put("description", r.description)
        put("preBoilVolumeL", r.preBoilVolumeL)
        put("postBoilVolumeL", r.postBoilVolumeL)
        put("fermenterVolumeL", r.fermenterVolumeL)
        put("efficiency", r.efficiency.asPercent().roundTo(1))
        put("boilTimeMin", r.boilTimeMin)
        putJsonArray("mashSteps") { r.mashSteps.forEach { add(mashStep(it.tempC, it.timeMin)) } }
        putJsonArray("fermentables") {
            r.fermentables.inBrewOrder().forEach {
                add(buildJsonObject {
                    put("name", it.name); put("amountKg", it.amountKg); put("type", it.type.code); put("usage", it.usage.code)
                    put("boilTimeMin", it.boilTimeMin); put("colourEbc", it.colourEbc); put("extractPercent", it.extractPercent)
                })
            }
        }
        putJsonArray("hops") {
            r.hops.inBrewOrder().forEach {
                add(buildJsonObject {
                    put("name", it.name); put("amountG", it.amountG); put("alphaAcid", it.alphaAcid); put("usage", it.usage.code)
                    put("boilTimeMin", it.boilTimeMin); put("dryHopDays", it.dryHopDays)
                })
            }
        }
        putJsonArray("yeasts") {
            r.yeasts.inBrewOrder().forEach {
                add(buildJsonObject { put("name", it.name); put("attenuation", it.attenuation.asPercent().roundTo(1)); put("usage", it.usage.code) })
            }
        }
        putJsonArray("extras") {
            r.extras.inBrewOrder().forEach {
                add(buildJsonObject {
                    put("name", it.name); put("amount", it.amount); put("unit", it.unit.code); put("usage", it.usage.code)
                    put("boilTimeMin", it.boilTimeMin)
                })
            }
        }
        r.water?.let { put("water", waterInputJson(it)) }
    }

    private fun brewJson(recipe: Recipe, b: Batch) = buildJsonObject {
        put("recipeNo", recipe.no)
        put("recipeName", recipe.name)
        put("batchNo", b.no)
        put("url", publicUrl?.let { "$it/recipes/${recipe.no}/batches/${b.no}" })
        put("brewDate", b.brewDate?.toString())
        put("packagedDate", b.packagedDate?.toString())
        put("og", b.measuredOg)
        put("fg", b.measuredFg)
        put("abv", b.abv?.roundTo(2))
        put("efficiencyPercent", b.mashEfficiency?.asPercent()?.roundTo(1))
        put("preBoilVolumeL", b.measuredPreBoilVolumeL)
        put("postBoilVolumeL", b.measuredPostBoilVolumeL)
        put("fermenterVolumeL", b.measuredFermenterVolumeL)
        put("notes", b.notes)
        b.water?.let { snapshot ->
            put("water", waterInputJson(snapshot.water))
            put("waterEstimate", waterJson(recipe.waterReport(snapshot, prefs)))
        }
    }
}

private fun mashStep(tempC: Double, timeMin: Int) = buildJsonObject { put("tempC", tempC); put("timeMin", timeMin) }

private fun estimate(s: RecipeStats) = buildJsonObject {
    put("og", s.og.roundTo(3))
    put("fg", s.fg.roundTo(3))
    put("abv", s.abv.roundTo(2))
    put("ibu", s.ibu.roundTo(1))
    put("colourEbc", s.colourEbc.roundTo(1))
}

private fun ions(p: WaterProfile) = buildJsonObject {
    put("calcium", p.calcium.roundTo(1))
    put("magnesium", p.magnesium.roundTo(1))
    put("sodium", p.sodium.roundTo(1))
    put("chloride", p.chloride.roundTo(1))
    put("sulphate", p.sulphate.roundTo(1))
    put("bicarbonate", p.bicarbonate.roundTo(1))
}

private fun waterInputJson(w: RecipeWater) = buildJsonObject {
    put("source", w.source); put("blendSource", w.blendSource); put("blendPercent", w.blendPercent)
    put("gypsumG", w.gypsumG); put("calciumChlorideG", w.calciumChlorideG); put("epsomG", w.epsomG)
    put("tableSaltG", w.tableSaltG); put("bakingSodaG", w.bakingSodaG)
    put("acid", w.acid.code); put("acidMl", w.acidMl); put("campdenTablets", w.campdenTablets); put("target", w.target?.code)
}

private fun waterJson(r: WaterReport) = buildJsonObject {
    put("mgPerL", ions(r.profile))
    put("chlorideToSulphate", r.profile.chlorideToSulphate?.roundTo(2))
    put("balance", r.balance?.label)
    put("mashPh", r.mashPh?.roundTo(2))
    put("mashPhRange", "$MIN_MASH_PH-$MAX_MASH_PH")
    put("targetMashPh", TARGET_MASH_PH)
    put("acid", r.acid.code)
    put("acidMlForTarget", r.acidForTargetMl?.roundTo(1))
    r.saltsForTarget?.let { s ->
        put("saltsForTarget", buildJsonObject { put("gypsumG", s.gypsumG.roundTo(1)); put("calciumChlorideG", s.calciumChlorideG.roundTo(1)) })
    }
    put("waterL", r.totalL.roundTo(1))
    put("strikeL", r.strikeL?.roundTo(1))
    put("spargeL", r.spargeL?.roundTo(1))
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.limit() = (int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

// The web form quietly falls back to a default for an unknown code; an agent is better told, so it can correct itself.
private fun validate(recipe: JsonObject) {
    val problems = mutableListOf<String>()
    if ((recipe["name"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) problems += "name is required"

    fun rows(key: String) = (recipe[key] as? JsonArray).orEmpty().mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { Triple(key, i, it) } }
    fun <E> code(row: Triple<String, Int, JsonObject>, field: String, lookup: CodeLookup<E>): E? where E : Enum<E>, E : Coded {
        val value = row.third.string(field) ?: return null
        return lookup.fromCodeOrNull(value) ?: null.also {
            problems += "${row.first}[${row.second}].$field \"$value\" must be one of ${lookup.all.joinToString { it.code }}"
        }
    }
    fun positive(row: Triple<String, Int, JsonObject>, field: String) {
        val v = (row.third[field] as? JsonPrimitive)?.doubleOrNull
        if (v == null || v <= 0.0) problems += "${row.first}[${row.second}].$field must be a positive number"
    }
    fun named(row: Triple<String, Int, JsonObject>) {
        if (row.third.string("name").isNullOrBlank()) problems += "${row.first}[${row.second}].name is required"
    }
    fun numbers(where: String, obj: JsonObject, vararg fields: String) = fields.forEach { field ->
        val value = obj[field]
        if (value != null && value !is JsonNull && (value as? JsonPrimitive)?.doubleOrNull == null) problems += "$where$field must be a number"
    }

    (recipe["water"] as? JsonObject)?.let { water ->
        val fields = listOf("blendPercent", "gypsumG", "calciumChlorideG", "epsomG", "tableSaltG", "bakingSodaG", "acidMl", "campdenTablets")
        numbers("water.", water, *fields.toTypedArray())
        water.string("acid")?.let { code ->
            if (Acid.fromCodeOrNull(code) == null) problems += "water.acid \"$code\" must be one of ${Acid.entries.joinToString { it.code }}"
        }
        water.string("target")?.let { code ->
            if (FlavourBalance.fromCodeOrNull(code) == null) problems += "water.target \"$code\" must be one of ${FlavourBalance.entries.joinToString { it.code }}"
        }
        fields.forEach { field -> (water[field] as? JsonPrimitive)?.doubleOrNull?.let { if (it < 0.0) problems += "water.$field must not be negative" } }
    }
    numbers("", recipe, "preBoilVolumeL", "postBoilVolumeL", "fermenterVolumeL", "efficiency", "boilTimeMin")
    rows("mashSteps").forEach { row ->
        listOf("tempC", "timeMin").forEach { if (row.third[it] == null) problems += "mashSteps[${row.second}].$it is required" }
        numbers("mashSteps[${row.second}].", row.third, "tempC", "timeMin")
    }

    rows("fermentables").forEach { row ->
        named(row); positive(row, "amountKg")
        numbers("fermentables[${row.second}].", row.third, "colourEbc", "extractPercent", "boilTimeMin")
        val type = code(row, "type", FermentableType) ?: FermentableType.default
        val usage = code(row, "usage", FermentableUsage) ?: FermentableUsage.default
        if (usage !in type.usages) problems += "fermentables[${row.second}]: a ${type.code} can only be used in ${type.usages.joinToString { it.code }}"
    }
    rows("hops").forEach { row ->
        named(row); positive(row, "amountG"); positive(row, "alphaAcid")
        numbers("hops[${row.second}].", row.third, "boilTimeMin", "dryHopDays")
        val usage = code(row, "usage", HopUsage) ?: HopUsage.default
        // Without a time the hop drops out of the IBU estimate unnoticed.
        if (usage.inBoil && row.third.int("boilTimeMin") == null) problems += "hops[${row.second}].boilTimeMin is required for a ${usage.code} hop"
    }
    rows("yeasts").forEach { row -> named(row); code(row, "usage", YeastUsage); numbers("yeasts[${row.second}].", row.third, "attenuation") }
    rows("extras").forEach { row ->
        named(row); positive(row, "amount"); code(row, "unit", AmountUnit); code(row, "usage", ExtraUsage)
        numbers("extras[${row.second}].", row.third, "boilTimeMin")
    }

    if (problems.isNotEmpty()) throw ToolError("The recipe has problems:\n- " + problems.joinToString("\n- "))
}
