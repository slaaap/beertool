package uk.beertool.web

import kotlinx.html.*
import uk.beertool.brewing.WaterProfile
import uk.beertool.common.MISSING
import uk.beertool.common.asPercent
import uk.beertool.common.fmt
import uk.beertool.common.prettify
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.MAX_UNMALTED_FRACTION
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.RecipeStats
import uk.beertool.user.BrewerPreferences

// Every recipe table uses the same seven slots, so their columns line up down the page.
internal const val SLOT_NAME = "c-name"
internal const val SLOT_AMOUNT = "c-amount"
internal const val SLOT_WHAT = "c-what"
internal const val SLOT_USE = "c-use"
internal const val SLOT_TIME = "c-time"
internal const val SLOT_PROP = "c-prop"
internal const val SLOT_LAST = "c-last"

internal fun TR.stepNumberCell() = td(SLOT_NAME) {}

internal fun TR.mashHead() {
    head("Rest", SLOT_NAME)
    numHead("Temp", "°C", SLOT_AMOUNT)
    emptySlot(SLOT_WHAT)
    emptySlot(SLOT_USE)
    numHead("Time", "min", SLOT_TIME)
    emptySlot(SLOT_PROP)
    emptySlot(SLOT_LAST)
}

// The stage supplies the usage, so only the types legal there are offered: no sugar in the mash.
internal class FermentableSection(val usage: FermentableUsage, val primary: Boolean) {
    val key get() = "ferm-${usage.code}"
    val bodyId get() = "$key-body"
    val types get() = FermentableType.all.filter { usage in it.usages }
    val label get() = if (usage == FermentableUsage.MASH) "Grist" else prettify(usage.name)
}

internal val FERMENTABLE_SECTIONS = FermentableUsage.all.map { usage ->
    FermentableSection(usage, primary = usage in setOf(FermentableUsage.MASH, FermentableUsage.BOIL, FermentableUsage.SECONDARY))
}

internal fun TR.fermentableStageColumns(section: FermentableSection) {
    head("Fermentable", SLOT_NAME)
    numHead("Amount", "kg", SLOT_AMOUNT)
    head("Type", SLOT_WHAT)
    emptySlot(SLOT_USE)
    if (section.usage.takesTime) numHead("Time", "min", SLOT_TIME) else emptySlot(SLOT_TIME)
    numHead("EBC", slot = SLOT_PROP)
    numHead("Extract", "%", SLOT_LAST)
}

// Each hop stage shows only the time that means something there: minutes in the kettle or days in the fermenter.
internal class HopSection(
    val usage: HopUsage,
    val timeLabel: String?,
    val timeUnit: String?,
    val timeField: String?,
    val primary: Boolean,
) {
    val key get() = "hop-${usage.code}"
    val bodyId get() = "$key-body"

    fun timeOf(hop: RecipeHop) = when (timeField) {
        "boilTimeMin" -> hop.boilTimeMin
        "dryHopDays" -> hop.dryHopDays
        else -> null
    }

    fun note(prefs: BrewerPreferences, boilTimeMin: Int) = when (usage) {
        HopUsage.BOIL -> "$boilTimeMin min boil"
        HopUsage.WHIRLPOOL -> "hops in at ${fmt(prefs.whirlpoolTempC, 0)} °C"
        else -> null
    }
}

internal val HOP_SECTIONS = listOf(
    HopSection(HopUsage.MASH, null, null, null, primary = false),
    HopSection(HopUsage.FIRST_WORT, "Time", "min", "boilTimeMin", primary = false),
    HopSection(HopUsage.BOIL, "Time", "min", "boilTimeMin", primary = true),
    HopSection(HopUsage.WHIRLPOOL, "Stand", "min", "boilTimeMin", primary = true),
    HopSection(HopUsage.DRY_HOP, "Days", null, "dryHopDays", primary = true),
)

internal fun FlowContent.stageTitle(label: String, note: String? = null) {
    div("stage-head") {
        h3 { +label }
        note?.let { span("stage-note") { +it } }
    }
}

internal fun TR.hopStageColumns(section: HopSection) {
    head("Hop", SLOT_NAME)
    numHead("Amount", "g", SLOT_AMOUNT)
    emptySlot(SLOT_WHAT)
    emptySlot(SLOT_USE)
    section.timeLabel?.let { numHead(it, section.timeUnit, SLOT_TIME) } ?: emptySlot(SLOT_TIME)
    emptySlot(SLOT_PROP)
    numHead("Alpha", "%", SLOT_LAST)
}

internal fun TR.yeastHead() {
    head("Yeast", SLOT_NAME)
    emptySlot(SLOT_AMOUNT)
    emptySlot(SLOT_WHAT)
    head("Use", SLOT_USE)
    emptySlot(SLOT_TIME)
    emptySlot(SLOT_PROP)
    numHead("Attenuation", "%", SLOT_LAST)
}

internal fun TR.extraHead() {
    head("Extra", SLOT_NAME)
    numHead("Amount", slot = SLOT_AMOUNT)
    head("Unit", SLOT_WHAT)
    head("Use", SLOT_USE)
    numHead("Time", "min", SLOT_TIME)
    emptySlot(SLOT_PROP)
    emptySlot(SLOT_LAST)
}

internal class Ion(val field: String, val label: String, val of: (WaterProfile) -> Double)

internal val WATER_IONS = listOf(
    Ion("calcium", "Ca", WaterProfile::calcium),
    Ion("magnesium", "Mg", WaterProfile::magnesium),
    Ion("sodium", "Na", WaterProfile::sodium),
    Ion("chloride", "Cl", WaterProfile::chloride),
    Ion("sulphate", "SO₄", WaterProfile::sulphate),
    Ion("bicarbonate", "HCO₃", WaterProfile::bicarbonate),
)

internal fun TR.actionSlot() = th(classes = "right c-act") {}

internal fun TR.head(label: String, slot: String) = th(classes = slot) { +label }

internal fun TR.numHead(label: String, unit: String? = null, slot: String? = null) =
    th(classes = listOfNotNull("num", slot).joinToString(" ")) { +(if (unit == null) label else "$label ($unit)") }

internal fun TR.emptySlot(slot: String) = th(classes = "$slot gap") {}

internal fun TR.emptyCell() = td("gap") {}

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
    tone: String? = null,
    action: (DIV.() -> Unit)? = null,
) {
    val classes = listOfNotNull("stat", "accent".takeIf { accent }, "last".takeIf { last }, tone).joinToString(" ")
    div(classes) {
        div("k") { +label }
        div("v") {
            if (valueId != null) span { id = valueId; +value } else +value
            unit?.let { span("u") { +" $it" } }
        }
        action?.invoke(this)
    }
}

internal fun FlowContent.unmaltedWarning(unmalted: Double) {
    if (unmalted <= MAX_UNMALTED_FRACTION) return
    div("alert") {
        +"${fmt(unmalted.asPercent(), 0)}% of the grist is unmalted, so the malt may not convert it all."
    }
}
