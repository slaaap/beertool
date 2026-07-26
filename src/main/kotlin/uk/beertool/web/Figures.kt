package uk.beertool.web

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import uk.beertool.recipe.MAX_UNMALTED_FRACTION
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import java.time.LocalDate

internal const val MISSING = "—"

internal fun fmt(v: Double?, decimals: Int) = v?.let { "%.${decimals}f".format(it) } ?: MISSING

internal fun fmt(d: LocalDate?) = d?.toString() ?: MISSING

internal fun prettify(name: String) = name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

internal const val SLOT_NAME = "c-name"
internal const val SLOT_AMOUNT = "c-amount"
internal const val SLOT_WHAT = "c-what"
internal const val SLOT_USE = "c-use"
internal const val SLOT_TIME = "c-time"

internal fun TR.stepNumberCell() = td(SLOT_NAME) {}

internal const val SLOT_PROP_NARROW = "c-prop-narrow"

internal fun TR.mashHead() {
    head("Rest", SLOT_NAME)
    numHead("Temp", "°C", SLOT_AMOUNT)
    emptySlot(SLOT_WHAT)
    emptySlot(SLOT_USE)

    numHead("Time", "min", SLOT_TIME)
    propertySpacer()
}

internal fun TR.fermentableHead() {
    head("Fermentable", SLOT_NAME)
    numHead("Amount", "kg", SLOT_AMOUNT)
    head("Type", SLOT_WHAT)
    head("Use", SLOT_USE)
    numHead("Time", "min", SLOT_TIME)
    numHead("EBC", slot = SLOT_PROP_NARROW)
    numHead("Extract", "%")
}

internal fun TR.hopHead() {
    head("Hop", SLOT_NAME)
    numHead("Amount", "g", SLOT_AMOUNT)
    emptySlot(SLOT_WHAT)
    head("Use", SLOT_USE)
    numHead("Time", "min", SLOT_TIME)
    numHead("Alpha", "%")
}

internal fun TR.yeastHead() {
    head("Yeast", SLOT_NAME)
    emptySlot(SLOT_AMOUNT)
    emptySlot(SLOT_WHAT)
    head("Use", SLOT_USE)
    emptySlot(SLOT_TIME)
    numHead("Attenuation", "%")
}

internal fun TR.extraHead() {
    head("Extra", SLOT_NAME)
    numHead("Amount", slot = SLOT_AMOUNT)
    head("Unit", SLOT_WHAT)
    head("Use", SLOT_USE)
    numHead("Time", "min", SLOT_TIME)
    propertySpacer()
}

internal fun TR.actionSlot() = th(classes = "right c-act") {}

internal fun TR.head(label: String, slot: String) = th(classes = slot) { +label }

internal fun TR.numHead(label: String, unit: String? = null, slot: String? = null) =
    th(classes = listOfNotNull("num", slot).joinToString(" ")) { +(if (unit == null) label else "$label ($unit)") }

internal fun TR.emptySlot(slot: String) = th(classes = slot) {}

internal fun TR.propertySpacer() = th {}

internal fun TR.emptyCell() = td {}

internal fun TR.numCell(v: Double?, decimals: Int) = td("num") { +fmt(v, decimals) }

internal fun TR.numCell(v: Int?) = td("num") { +(v?.toString() ?: MISSING) }

internal fun TR.numInput(name: String, value: String?, step: String, max: String? = null, placeholder: String? = null) =
    td("num") {
        numberInput(name = name) {
            this.value = value ?: ""
            placeholder?.let { this.placeholder = it }
            numAttrs(step, max)
        }
    }

internal fun INPUT.numAttrs(step: String, max: String? = null) {
    attributes["step"] = step
    attributes["min"] = "0"
    max?.let { attributes["max"] = it }
}

internal fun DIV.statsTiles(stats: RecipeStats) {
    statTile("OG", fmt(stats.og, 3), null)
    statTile("FG", fmt(stats.fg, 3), null)
    statTile("ABV", fmt(stats.abv, 1), "%", accent = true)
    statTile("IBU", fmt(stats.ibu, 0), null)
    statTile("Colour", fmt(stats.colourEbc, 0), "EBC", last = true)
}

internal fun DIV.brewDayTiles(recipe: Recipe) {
    statTile("Pre-boil", fmt(recipe.preBoilVolumeL, 1), "L")
    statTile("Post-boil", fmt(recipe.postBoilVolumeL, 1), "L")
    statTile("Fermenter", fmt(recipe.fermenterVolumeL, 1), "L")
    statTile("Efficiency", fmt(recipe.efficiency.asPercent(), 0), "%")
    statTile("Boil", recipe.boilTimeMin.toString(), "min", last = true)
}

internal fun DIV.statTile(
    label: String,
    value: String,
    unit: String?,
    accent: Boolean = false,
    last: Boolean = false,
    valueId: String? = null,
) {
    val classes = buildString { append("stat"); if (accent) append(" accent"); if (last) append(" last") }
    div(classes) {
        div("k") { +label }
        div("v") {
            if (valueId != null) span { id = valueId; +value } else +value
            unit?.let { span("u") { +" $it" } }
        }
    }
}

internal fun FlowContent.unmaltedWarning(unmalted: Double) {
    if (unmalted <= MAX_UNMALTED_FRACTION) return
    div("alert") {
        +"${fmt(unmalted.asPercent(), 0)}% of the grist is unmalted — the malt may not convert it all."
    }
}

fun statsFragmentHtml(stats: RecipeStats): String = createHTML().div("stats") { statsTiles(stats) }
