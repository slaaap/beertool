package uk.beertool.web

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import uk.beertool.batch.Batch
import uk.beertool.brewing.Acid
import uk.beertool.common.MISSING
import uk.beertool.common.fmt
import uk.beertool.common.roundTo
import uk.beertool.recipe.FlavourBalance
import uk.beertool.recipe.MAX_MASH_PH
import uk.beertool.recipe.MIN_MASH_PH
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.TARGET_MASH_PH
import uk.beertool.recipe.WaterAddition
import uk.beertool.recipe.WaterReport
import uk.beertool.recipe.defaultRecipeWater
import uk.beertool.recipe.describe
import uk.beertool.recipe.doses
import uk.beertool.recipe.waterReport
import uk.beertool.user.BrewerPreferences

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
            else addButton("Add water", "waterOpen(this)")
        }
        if (names.isEmpty()) return@div
        div("water-panel") {
            hidden = !open
            div("grid g3 water-row" + if (mixing) "" else " solo") {
                removableField("Water", "waterClose(this)", "Remove water") {
                    waterSelect("water.source", names, water?.source ?: names.first(), disabled = !open)
                }
                if (names.size > 1) {
                    removableField("Mixed with", "waterUnmix(this)", "Stop mixing", classes = "water-mix", hidden = !mixing) {
                        waterSelect("water.blendSource", names, water?.blendSource ?: names[1], disabled = !mixing)
                    }
                    div("field water-mix") {
                        hidden = !mixing
                        label { +"Share (%)" }
                        numberInput(name = "water.blendPercent") {
                            value = fmt(water?.blendPercent?.takeIf { mixing } ?: DEFAULT_BLEND_PERCENT, 0)
                            numAttrs("5", max = "100"); disabled = !mixing
                        }
                    }
                    addButton("Mix", "waterMix(this)") { hidden = mixing; attributes["data-mix"] = "" }
                }
            }
            fieldGrid(columns = 3) {
                WaterAddition.entries.forEach { a ->
                    val amount = water?.let(a.amountIn)?.takeIf { it > 0.0 }
                    val label = a.labelIn(null)
                    val classes = if (a == WaterAddition.ACID) "acid" else null
                    removableField("$label (${a.unit})", "waterDrop(this)", "Remove ${label.lowercase()}", classes, hidden = amount == null, dataAdd = a.field) {
                        if (a == WaterAddition.ACID) div("acid-input") {
                            acidSelect(water?.acid ?: Acid.LACTIC_80, disabled = !open)
                            amountInput(a, amount, disabled = !open)
                        } else {
                            amountInput(a, amount, disabled = !open)
                        }
                    }
                }
            }
            div("water-actions") {
                details(classes = "more-stages") {
                    summary(classes = "btn small") { iconSvg(Icon.PLUS); span { +"Salt" } }
                    div {
                        WaterAddition.entries.filter { it.salt != null }.forEach { a ->
                            button(type = ButtonType.button, classes = "btn small") {
                                attributes["data-adds"] = a.field
                                hidden = water?.let(a.amountIn)?.let { it > 0.0 } == true
                                onClick = "waterAdd('${a.field}')"
                                +a.labelIn(null)
                            }
                        }
                    }
                }
                WaterAddition.entries.filter { it.salt == null }.forEach { a ->
                    addButton(a.labelIn(null), "waterAdd('${a.field}')") {
                        attributes["data-adds"] = a.field
                        hidden = water?.let(a.amountIn)?.let { it > 0.0 } == true
                    }
                }
                submitLabel?.let { submitInput(classes = "btn primary small") { value = it } }
            }
        }
    }
}

private fun FlowContent.removableField(
    label: String,
    onRemove: String,
    removeLabel: String,
    classes: String? = null,
    hidden: Boolean = false,
    dataAdd: String? = null,
    block: DIV.() -> Unit,
) {
    div(listOfNotNull("field", classes).joinToString(" ")) {
        this.hidden = hidden
        dataAdd?.let { attributes["data-add"] = it }
        div("field-head") {
            label { +label }
            button(type = ButtonType.button, classes = "field-remove") {
                onClick = onRemove
                title = removeLabel
                attributes["aria-label"] = removeLabel
                +"×"
            }
        }
        block()
    }
}

private const val DEFAULT_BLEND_PERCENT = 50.0

private fun FlowContent.amountInput(addition: WaterAddition, amount: Double?, disabled: Boolean) =
    numberInput(name = "water.${addition.field}") {
        value = amount?.toString() ?: ""; numAttrs("0.1"); this.disabled = disabled
    }

private fun FlowContent.acidSelect(chosen: Acid, disabled: Boolean) {
    select {
        name = "water.acid"
        this.disabled = disabled
        Acid.entries.forEach { acid -> option { value = acid.code; if (acid == chosen) selected = true; +acid.shortLabel } }
    }
}

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
    val inRange = report.mashPh?.let { it in MIN_MASH_PH..MAX_MASH_PH }
    val acidMl = report.acidForTargetMl
    div(if (canApply) "stats trio chem" else "stats duo chem") {
        statTile("Cl : SO₄", fmt(report.profile.chlorideToSulphate, 2), report.balance?.label) {
            if (canApply) balanceLinks(report)
        }
        statTile("Mash pH", fmt(report.mashPh, 2), "$MIN_MASH_PH-$MAX_MASH_PH", tone = if (inRange == false) "bad" else if (inRange == true) "good" else null)
        if (canApply) statTile("${report.acid.shortLabel} for $TARGET_MASH_PH", fmt(acidMl, 1), "ml") {
            if (acidMl != null && acidMl >= 0.1 && acidMl.roundTo(1) != report.water.acidMl.roundTo(1)) {
                button(type = ButtonType.button, classes = "tile-action") {
                    onClick = "waterUseAcid(this, '${fmt(acidMl, 1)}')"
                    +"Use this"
                }
            }
        }
    }
}

// Tapping a balance fills in its gypsum and calcium chloride; the one the salts match is marked, and a dot shows the one that suits the beer.
private fun DIV.balanceLinks(report: WaterReport) {
    div("balance-links") {
        FlavourBalance.entries.forEach { balance ->
            val salts = report.saltsFor[balance] ?: return@forEach
            val gypsum = salts.gypsumG.roundTo(1)
            val chloride = salts.calciumChlorideG.roundTo(1)
            val current = gypsum == report.water.gypsumG.roundTo(1) && chloride == report.water.calciumChlorideG.roundTo(1)
            val classes = listOfNotNull("active".takeIf { current }, "recommended".takeIf { balance == report.recommended })
            button(type = ButtonType.button, classes = classes.joinToString(" ").ifEmpty { null }) {
                disabled = current
                title = "${fmt(gypsum, 1)} g gypsum + ${fmt(chloride, 1)} g calcium chloride" +
                    if (balance == report.recommended) ", suggested for this beer" else ""
                onClick = "waterUseSalts(this, '${fmt(gypsum, 1)}', '${fmt(chloride, 1)}')"
                +balance.label
            }
        }
    }
}

internal fun FlowContent.waterSummary(water: RecipeWater?, report: WaterReport?) {
    if (water == null) return
    sectionHead("Water")
    div("stats auto") {
        statTile("Water", water.describe(), null, tone = "text")
        water.additions.forEach { a -> statTile(a.labelIn(water), fmt(a.amountIn(water), 1), a.unit) }
    }
    waterResult(report)
}

internal fun FlowContent.brewDayWater(recipe: Recipe, batch: Batch, prefs: BrewerPreferences) {
    val water = batch.water?.water
    val report = batch.water?.let { recipe.waterReport(it, prefs) }
    if (batch.waterLocked && (water == null || report == null)) return
    div {
        id = "water"
        sectionHead("Water")
        if (water != null && report != null) {
            treatmentPlan(water, report)
            mashPhTiles(report, canApply = !batch.waterLocked)
            report.warnings.forEach { div("alert") { +it } }
        }
        if (batch.waterLocked) return@div
        val form: FlowContent.() -> Unit = {
            form(action = "/recipes/${recipe.no}/batches/${batch.no}/water", method = FormMethod.post, classes = "brew-water") {
                waterEditor(water, prefs, submitLabel = "Update water")
            }
        }
        // The plan is what the brewer follows at the kettle, so changing it stays folded away until needed.
        if (water == null) form() else details(classes = "water-change") {
            summary(classes = "btn small") { +"Change water" }
            form()
        }
    }
}

private fun FlowContent.treatmentPlan(water: RecipeWater, report: WaterReport) {
    val strike = report.strikeL ?: return
    val sparge = report.spargeL ?: return
    table("mash-plan water-plan") {
        thead {
            tr {
                th { +water.describe() }
                th(classes = "num") { +"Strike ${fmt(strike, 1)} L" }
                th(classes = "num") { +"Sparge ${fmt(sparge, 1)} L" }
            }
        }
        tbody {
            report.doses(water).forEach { dose ->
                val unit = dose.addition.unit
                tr {
                    td { +dose.addition.labelIn(water) }
                    td("num") { +"${fmt(dose.strike, 1)} $unit" }
                    td("num") { +(if (dose.mashOnly || sparge <= 0.0) MISSING else "${fmt(dose.sparge, 1)} $unit") }
                }
            }
        }
    }
}

fun estimatesFragmentHtml(stats: RecipeStats, water: WaterReport?): String = createHTML().div {
    div { attributes["data-slot"] = "stats"; div("stats") { statsTiles(stats) } }
    div { attributes["data-slot"] = "water"; waterResult(water, canApply = true) }
}
