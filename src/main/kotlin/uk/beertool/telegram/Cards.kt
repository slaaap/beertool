package uk.beertool.telegram

import uk.beertool.batch.Batch
import uk.beertool.batch.BatchSummary
import uk.beertool.recipe.BoilAddition
import uk.beertool.recipe.BrewStage
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.RecipeSummary
import uk.beertool.recipe.boilAdditions
import uk.beertool.recipe.inBrewOrder
import uk.beertool.recipe.mashWater
import uk.beertool.user.BrewerPreferences
import uk.beertool.web.fmt
import uk.beertool.web.prettify

// Telegram rejects messages longer than 4096 characters.
private const val MAX_MESSAGE = 4000

fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun bold(s: String) = "<b>${s.escapeHtml()}</b>"

private fun num(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v).trimEnd('0')

private fun stage(usage: BrewStage, minutes: Int?) =
    prettify((usage as Enum<*>).name).lowercase() + if (usage.inBoil && minutes != null) " $minutes min" else ""

// Cards are built line by line and every tag closes on its own line, so cutting between lines keeps the HTML valid.
private fun clip(lines: List<String>): String {
    val kept = mutableListOf<String>()
    var length = 0
    for (line in lines) {
        if (length + line.length + 1 > MAX_MESSAGE) return (kept + "…").joinToString("\n")
        kept += line
        length += line.length + 1
    }
    return kept.joinToString("\n")
}

fun recipeTitle(recipe: Recipe) = "${bold(recipe.name)} #${recipe.no}" + (recipe.style?.let { "\n<i>${it.escapeHtml()}</i>" } ?: "")

fun statsLine(stats: RecipeStats) =
    "OG ${fmt(stats.og, 3)} · FG ${fmt(stats.fg, 3)} · ${fmt(stats.abv, 1)}% ABV · ${fmt(stats.ibu, 0)} IBU · ${fmt(stats.colourEbc, 0)} EBC"

fun recipeCard(recipe: Recipe, stats: RecipeStats): String {
    val lines = mutableListOf(recipeTitle(recipe), statsLine(stats))
    lines += "${num(recipe.fermenterVolumeL)} L into the fermenter · ${recipe.boilTimeMin} min boil · ${fmt(recipe.efficiency * 100, 0)}% efficiency"

    fun section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        lines += ""
        lines += bold(title)
        items.forEach { lines += "• $it" }
    }
    section("Mash", recipe.mashSteps.map { "${num(it.tempC)} °C for ${it.timeMin} min" })
    section("Fermentables", recipe.fermentables.inBrewOrder().map {
        "${num(it.amountKg)} kg ${it.name.escapeHtml()} · ${stage(it.usage, it.boilTimeMin)}"
    })
    section("Hops", recipe.hops.inBrewOrder().map {
        val use = if (it.usage == HopUsage.DRY_HOP && it.dryHopDays != null) "dry hop ${it.dryHopDays} days" else stage(it.usage, it.boilTimeMin)
        "${num(it.amountG)} g ${it.name.escapeHtml()} (${num(it.alphaAcid)}%) · $use"
    })
    section("Yeast", recipe.yeasts.inBrewOrder().map { "${it.name.escapeHtml()} · ${stage(it.usage, null)}" })
    section("Extras", recipe.extras.inBrewOrder().map {
        "${num(it.amount)} ${it.unit.code} ${it.name.escapeHtml()} · ${stage(it.usage, it.boilTimeMin)}"
    })
    return clip(lines)
}

fun batchStatus(batch: Batch) = when {
    batch.packagedDate != null -> "packaged ${fmt(batch.packagedDate)}"
    batch.measuredOg == null -> "brew day"
    else -> "fermenting"
}

fun batchCard(recipe: Recipe, batch: Batch, estimate: RecipeStats): String {
    val lines = mutableListOf(
        "${bold(recipe.name)} #${recipe.no} · batch ${batch.no}",
        "Brewed ${fmt(batch.brewDate)} · ${batchStatus(batch)}",
        "",
        "OG ${fmt(batch.measuredOg, 3)} (plan ${fmt(estimate.og, 3)})",
        "FG ${fmt(batch.measuredFg, 3)} (plan ${fmt(estimate.fg, 3)})",
        "Pre-boil ${fmt(batch.measuredPreBoilVolumeL, 1)} L (plan ${fmt(recipe.preBoilVolumeL, 1)})",
        "Post-boil ${fmt(batch.measuredPostBoilVolumeL, 1)} L (plan ${fmt(recipe.postBoilVolumeL, 1)})",
        "Fermenter ${fmt(batch.measuredFermenterVolumeL, 1)} L (plan ${fmt(recipe.fermenterVolumeL, 1)})",
    )
    if (batch.abv != null || batch.mashEfficiency != null) {
        lines += "${fmt(batch.abv, 1)}% ABV · ${fmt(batch.mashEfficiency?.times(100), 0)}% efficiency"
    }
    batch.notes?.let {
        lines += ""
        lines += bold("Notes")
        lines += it.escapeHtml().lines()
    }
    return clip(lines)
}

fun brewDayCard(recipe: Recipe, batch: Batch, prefs: BrewerPreferences): String {
    val lines = mutableListOf("Brew day: ${bold(recipe.name)} #${recipe.no} · batch ${batch.no}")
    recipe.mashWater(prefs.mashThicknessLPerKg, prefs.grainAbsorptionLPerKg)?.let {
        lines += ""
        lines += bold("Water")
        lines += "Strike ${fmt(it.strikeVolumeL, 1)} L at ${fmt(it.strikeTempC, 1)} °C · sparge ${fmt(it.spargeVolumeL, 1)} L"
    }
    if (recipe.mashSteps.isNotEmpty()) {
        lines += ""
        lines += bold("Mash")
        recipe.mashSteps.forEach { lines += "• ${num(it.tempC)} °C for ${it.timeMin} min" }
    }
    lines += ""
    lines += bold("Boil · ${recipe.boilTimeMin} min")
    recipe.boilAdditions().forEach { lines += "• ${additionLine(it)}" }
    lines += ""
    lines += "Send readings as they come, e.g. <code>pre 27.5</code>, <code>og 1.052 post 23</code>, " +
        "<code>fermenter 21</code>, <code>fg 1.010</code> or <code>note smelled great</code>."
    return clip(lines)
}

fun additionLine(addition: BoilAddition) =
    (if (addition.minutesBeforeEnd == 0) "flame-out" else "${addition.minutesBeforeEnd} min") + ": ${addition.what.escapeHtml()}"

fun boilAlertText(recipe: Recipe, alert: BoilAlert, minutesLeft: Long): String {
    val lines = mutableListOf<String>()
    val now = alert.additions.filter { it.minutesBeforeEnd > 0 }
    if (now.isNotEmpty()) {
        lines += "${bold(recipe.name)}: add now"
        now.forEach { lines += "• ${it.what.escapeHtml()}" }
        if (!alert.flameOut) lines += "<i>$minutesLeft min to flame-out</i>"
    }
    if (alert.flameOut) {
        lines += "${bold(recipe.name)}: flame-out!"
        alert.additions.filter { it.minutesBeforeEnd == 0 }.forEach { lines += "• ${it.what.escapeHtml()}" }
    }
    return clip(lines)
}

fun recipeLine(summary: RecipeSummary) = "#${summary.no} ${summary.name}" + (summary.style?.let { " · $it" } ?: "")

fun batchLine(summary: BatchSummary) =
    "${summary.recipeName} #${summary.recipeNo}/${summary.batch.no} · ${fmt(summary.batch.brewDate)} · ${batchStatus(summary.batch)}"
