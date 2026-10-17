package uk.beertool.web

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import uk.beertool.batch.Batch
import uk.beertool.brewing.Salt
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
    val water = if (recipe == null) prefs.defaultRecipeWater() else recipe.water
    sectionHead("Water")
    waterEditor(water, prefs)
    val report = recipe?.waterReport(prefs) ?: NewRecipe(name = "", water = water).waterReport(prefs)
    div { id = "water"; waterResult(report, canApply = true) }
}

private fun FlowContent.waterEditor(water: RecipeWater?, prefs: BrewerPreferences, submitLabel: String? = null) {
    val names = (prefs.waterSources.map { it.name } + listOfNotNull(water?.source, water?.blendSource)).distinct()
    val open = water != null
    val mixing = water?.blends == true
    div("water-editor") {
        attributes["data-water-editor"] = ""
        div("water-add") {
            hidden = open
            if (names.isEmpty()) btnLink("/settings", "Add water sources in Settings", Icon.PLUS)
            else button(type = ButtonType.button, classes = "btn small") {
                onClick = "waterOpen(this)"
                span("ico") { unsafe { +Icon.PLUS.markup() } }
                span { +"Add water" }
            }
        }
        if (names.isEmpty()) return@div
        div("water-panel") {
            hidden = !open
            div("water-line") {
                field("Water") { waterSelect("water.source", names, water?.source ?: names.first(), disabled = !open) }
                rowRemoveButton("waterClose(this)", "Remove water")
            }
            if (names.size > 1) {
                div("water-line water-mix") {
                    hidden = !mixing
                    field("Mixed with") {
                        waterSelect("water.blendSource", names, water?.blendSource ?: names[1], disabled = !mixing)
                    }
                    field("Share (%)") {
                        numberInput(name = "water.blendPercent") {
                            value = fmt(water?.blendPercent?.takeIf { mixing } ?: DEFAULT_BLEND_PERCENT, 0)
                            numAttrs("5", max = "100"); disabled = !mixing
                        }
                    }
                    rowRemoveButton("waterUnmix(this)", "Stop mixing")
                }
            }
            table("water-salts") {
                tbody {
                    ADDITIONS.forEach { a ->
                        val amount = water?.let(a.of)?.takeIf { it > 0.0 }
                        tr {
                            attributes["data-add"] = a.field
                            hidden = amount == null
                            td { +a.label }
                            td("num") {
                                numberInput(name = "water.${a.field}") {
                                    value = amount?.toString() ?: ""; numAttrs("0.1"); disabled = !open
                                }
                            }
                            td("unit") { +a.unit }
                            td("right") { rowRemoveButton("waterDrop(this)", "Remove ${a.label.lowercase()}") }
                        }
                    }
                }
            }
            div("water-actions") {
                if (names.size > 1) button(type = ButtonType.button, classes = "btn small") {
                    hidden = mixing
                    attributes["data-mix"] = ""
                    onClick = "waterMix(this)"
                    span("ico") { unsafe { +Icon.PLUS.markup() } }
                    span { +"Mix" }
                }
                details(classes = "more-stages") {
                    summary(classes = "btn small") { +"Add salt" }
                    div {
                        ADDITIONS.filterNot { it.mashOnly }.forEach { a ->
                            button(type = ButtonType.button, classes = "btn small") {
                                attributes["data-adds"] = a.field
                                hidden = water?.let(a.of)?.let { it > 0.0 } == true
                                onClick = "waterAdd('${a.field}')"
                                +a.label
                            }
                        }
                    }
                }
                ADDITIONS.filter { it.mashOnly }.forEach { a ->
                    button(type = ButtonType.button, classes = "btn small") {
                        attributes["data-adds"] = a.field
                        hidden = water?.let(a.of)?.let { it > 0.0 } == true
                        onClick = "waterAdd('${a.field}')"
                        span("ico") { unsafe { +Icon.PLUS.markup() } }
                        span { +a.label }
                    }
                }
                submitLabel?.let { submitInput(classes = "btn primary small") { value = it } }
            }
        }
    }
}

private const val DEFAULT_BLEND_PERCENT = 50.0

private fun FlowContent.waterSelect(name: String, names: List<String>, chosen: String, disabled: Boolean) {
    select {
        this.name = name
        this.disabled = disabled
        names.forEach { n -> option { value = n; if (n == chosen) selected = true; +n } }
    }
}

internal fun FlowContent.waterResult(report: WaterReport?, canApply: Boolean = false) {
    if (report == null) return
    div("stats six chem") {
        WATER_IONS.forEach { ion -> statTile(ion.label, fmt(ion.of(report.profile), 0), "mg/L") }
    }
    mashPhTiles(report, canApply)
    report.warnings.forEach { div("alert") { +it } }
}

private fun FlowContent.mashPhTiles(report: WaterReport, canApply: Boolean) {
    div("stats trio chem") {
        statTile("Cl : SO₄", fmt(report.profile.chlorideToSulphate, 2), report.balance?.label)
        statTile("Mash pH", fmt(report.mashPh, 2), null, accent = true)
        statTile("Lactic for $TARGET_MASH_PH", fmt(report.lacticForTargetMl, 1), "ml")
    }
    val lactic = report.lacticForTargetMl?.takeIf { canApply && it >= 0.1 } ?: return
    button(type = ButtonType.button, classes = "btn small use-lactic") {
        onClick = "waterUseLactic('${fmt(lactic, 1)}')"
        +"Use ${fmt(lactic, 1)} ml lactic acid"
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
    val water = batch.water?.water
    val report = batch.water?.let { recipe.waterReport(it, prefs) }
    if (batch.waterLocked && (water == null || report == null)) return
    sectionHead("Water")
    if (!batch.waterLocked) {
        form(action = "/recipes/${recipe.no}/batches/${batch.no}/water", method = FormMethod.post, classes = "brew-water") {
            id = "water"
            waterEditor(water, prefs, submitLabel = "Update water")
        }
    }
    if (water == null || report == null) return
    treatmentPlan(water, report)
    mashPhTiles(report, canApply = !batch.waterLocked)
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
    div { attributes["data-slot"] = "water"; waterResult(water, canApply = true) }
}
