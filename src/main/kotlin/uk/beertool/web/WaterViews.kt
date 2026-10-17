package uk.beertool.web

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import uk.beertool.batch.Batch
import uk.beertool.brewing.Salt
import uk.beertool.recipe.DISTILLED_WATER
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.TARGET_MASH_PH
import uk.beertool.recipe.WaterReport
import uk.beertool.recipe.defaultRecipeWater
import uk.beertool.recipe.describe
import uk.beertool.recipe.waterReport
import uk.beertool.user.BrewerPreferences

private class Addition(
    val field: String,
    val label: String,
    val unit: String,
    val of: (RecipeWater) -> Double,
    val mashOnly: Boolean = false,
)

private val ADDITIONS = listOf(
    Addition("gypsumG", prettify(Salt.GYPSUM.name), "g", RecipeWater::gypsumG),
    Addition("calciumChlorideG", prettify(Salt.CALCIUM_CHLORIDE.name), "g", RecipeWater::calciumChlorideG),
    Addition("epsomG", "Epsom salt", "g", RecipeWater::epsomG),
    Addition("tableSaltG", prettify(Salt.TABLE_SALT.name), "g", RecipeWater::tableSaltG),
    Addition("bakingSodaG", prettify(Salt.BAKING_SODA.name), "g", RecipeWater::bakingSodaG),
    Addition("lacticMl", "Lactic acid 80%", "ml", RecipeWater::lacticMl, mashOnly = true),
)

internal fun FlowContent.waterFormSection(recipe: Recipe?, prefs: BrewerPreferences) {
    val water = (if (recipe == null) prefs.defaultRecipeWater() else recipe.water) ?: RecipeWater()
    sectionHead("Water")
    waterInputs(water, prefs.waterSources.map { it.name })
    val report = recipe?.waterReport(prefs) ?: NewRecipe(name = "", water = prefs.defaultRecipeWater()).waterReport(prefs)
    div { id = "water"; waterResult(report) }
}

private fun FlowContent.waterInputs(water: RecipeWater, sourceNames: List<String>) {
    val names = (sourceNames + listOfNotNull(water.source, water.blendSource)).distinct()
    fieldGrid(columns = 3) {
        field("Water") { waterSelect("water.source", names, water.source) }
        field("Mixed with") { waterSelect("water.blendSource", names, water.blendSource) }
        field("Mix (%)") {
            numberInput(name = "water.blendPercent") {
                value = if (water.blends) fmt(water.blendPercent, 0) else ""; numAttrs("5", max = "100")
            }
        }
    }
    fieldGrid(columns = 3) {
        ADDITIONS.forEach { a ->
            field("${a.label} (${a.unit})") {
                numberInput(name = "water.${a.field}") {
                    value = a.of(water).takeIf { it > 0.0 }?.toString() ?: ""; numAttrs("0.1")
                }
            }
        }
    }
}

private fun FlowContent.waterSelect(name: String, names: List<String>, chosen: String?) {
    select {
        this.name = name
        option { value = ""; if (chosen == null) selected = true; +DISTILLED_WATER }
        names.forEach { n -> option { value = n; if (n == chosen) selected = true; +n } }
    }
}

internal fun FlowContent.waterResult(report: WaterReport?) {
    if (report == null) return
    div("stats six chem") {
        WATER_IONS.forEach { ion -> statTile(ion.label, fmt(ion.of(report.profile), 0), "mg/L") }
    }
    mashPhTiles(report)
    report.warnings.forEach { div("alert") { +it } }
}

private fun FlowContent.mashPhTiles(report: WaterReport) {
    div("stats trio chem") {
        statTile("Cl : SO₄", fmt(report.profile.chlorideToSulphate, 2), report.balance?.label)
        statTile("Mash pH", fmt(report.mashPh, 2), null, accent = true)
        statTile("Lactic for $TARGET_MASH_PH", fmt(report.lacticForTargetMl, 1), "ml")
    }
}

internal fun FlowContent.waterSummary(water: RecipeWater?, report: WaterReport?) {
    if (water == null) return
    sectionHead("Water")
    scrollTable("bill fit water-adds") {
        tbody {
            tr { td { +"Water" }; td { colSpan = "2"; +water.describe() } }
            ADDITIONS.filter { it.of(water) > 0.0 }.forEach { a ->
                tr { td { +a.label }; td("num") { +"${fmt(a.of(water), 1)} ${a.unit}" }; emptyCell() }
            }
        }
    }
    waterResult(report)
}

internal fun FlowContent.brewDayWater(recipe: Recipe, batch: Batch, prefs: BrewerPreferences) {
    val snapshot = batch.water
    val water = snapshot?.water ?: RecipeWater()
    val report = snapshot?.let { recipe.waterReport(it, prefs) }
    if (batch.waterLocked && report == null) return
    sectionHead("Water")
    if (!batch.waterLocked) {
        form(action = "/recipes/${recipe.no}/batches/${batch.no}/water", method = FormMethod.post, classes = "brew-water") {
            id = "water"
            waterInputs(water, prefs.waterSources.map { it.name })
            actionBar { submitInput(classes = "btn") { value = "Update water" } }
        }
    }
    if (report == null) return
    treatmentPlan(water, report)
    mashPhTiles(report)
    report.warnings.forEach { div("alert") { +it } }
}

private fun FlowContent.treatmentPlan(water: RecipeWater, report: WaterReport) {
    val strike = report.strikeL ?: return
    val sparge = report.spargeL ?: return
    val used = ADDITIONS.filter { it.of(water) > 0.0 }
    table("mash-plan water-plan") {
        thead {
            tr {
                th { +water.describe() }
                th(classes = "num") { +"Strike ${fmt(strike, 1)} L" }
                th(classes = "num") { +"Sparge ${fmt(sparge, 1)} L" }
            }
        }
        tbody {
            used.forEach { a ->
                val total = a.of(water)
                val toStrike = if (a.mashOnly) total else total * strike / report.totalL
                tr {
                    td { +a.label }
                    td("num") { +"${fmt(toStrike, 1)} ${a.unit}" }
                    td("num") { +(if (a.mashOnly || sparge <= 0.0) MISSING else "${fmt(total - toStrike, 1)} ${a.unit}") }
                }
            }
        }
    }
}

fun estimatesFragmentHtml(stats: RecipeStats, water: WaterReport?): String = createHTML().div {
    div { attributes["data-slot"] = "stats"; div("stats") { statsTiles(stats) } }
    div { attributes["data-slot"] = "water"; waterResult(water) }
}
