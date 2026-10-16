package uk.beertool.telegram

import uk.beertool.batch.Batch
import uk.beertool.batch.BatchSummary
import uk.beertool.batch.stats
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
import uk.beertool.web.MISSING
import uk.beertool.web.fmt
import uk.beertool.web.prettify
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Telegram rejects messages longer than 4096 characters.
private const val MAX_MESSAGE = 4000

fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun bold(s: String) = "<b>${s.escapeHtml()}</b>"

private fun num(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v).trimEnd('0')

private fun stage(usage: BrewStage, minutes: Int?) =
    prettify((usage as Enum<*>).name).lowercase() + if (usage.inBoil && minutes != null) " $minutes min" else ""

// Cards are built as a list of entries that each close their own tags, so cutting between entries keeps the HTML valid.
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

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private const val MAX_NOTES = 1500

fun friendlyDate(date: LocalDate?, today: LocalDate): String = when {
    date == null -> MISSING
    date.year == today.year -> date.format(DAY_MONTH)
    else -> date.format(DAY_MONTH_YEAR)
}

private fun String.escapeAttribute() = escapeHtml().replace("\"", "&quot;")

private fun days(n: Long) = if (n == 1L) "1 day" else "$n days"

private fun statusIcon(batch: Batch) = when {
    batch.packagedDate != null -> "🍺"
    batch.measuredOg == null -> "🔥"
    else -> "🫧"
}

fun batchStatus(batch: Batch, today: LocalDate) = statusIcon(batch) + " " + when {
    batch.packagedDate != null -> "packaged ${friendlyDate(batch.packagedDate, today)}"
    batch.measuredOg == null -> "brew day"
    else -> "fermenting"
}

private fun batchProgress(batch: Batch, today: LocalDate): String {
    val brewed = batch.brewDate
    val packaged = batch.packagedDate
    return when {
        packaged != null -> "Packaged ${friendlyDate(packaged, today)} · ${days(ChronoUnit.DAYS.between(packaged, today))} in the bottle"
        batch.measuredOg == null -> "Brew day"
        brewed != null -> "Fermenting · day ${ChronoUnit.DAYS.between(brewed, today)}"
        else -> "Fermenting"
    } + (brewed?.let { " · brewed ${friendlyDate(it, today)}" } ?: "")
}

private class Reading(val label: String, val key: String, val example: String, val actual: Double?, val plan: Double?, val decimals: Int)

private fun readings(recipe: Recipe, batch: Batch, estimate: RecipeStats) = listOf(
    Reading("OG", "og", "og 1.052", batch.measuredOg, estimate.og, 3),
    Reading("FG", "fg", "fg 1.010", batch.measuredFg, estimate.fg, 3),
    Reading("Pre-boil", "pre", "pre 27.5", batch.measuredPreBoilVolumeL, recipe.preBoilVolumeL, 1),
    Reading("Post-boil", "post", "post 23", batch.measuredPostBoilVolumeL, recipe.postBoilVolumeL, 1),
    Reading("Fermenter", "fermenter", "fermenter 21", batch.measuredFermenterVolumeL, recipe.fermenterVolumeL, 1),
)

fun batchCard(recipe: Recipe, batch: Batch, estimate: RecipeStats, today: LocalDate, publicUrl: String? = null): String {
    val name = "${recipe.name} #${recipe.no} · batch ${batch.no}".escapeHtml()
    val title = publicUrl?.let { "<a href=\"${"$it/recipes/${recipe.no}/batches/${batch.no}".escapeAttribute()}\">$name</a>" } ?: name
    val lines = mutableListOf("${statusIcon(batch)} <b>$title</b>", batchProgress(batch, today))

    val headline = listOfNotNull(
        batch.abv?.let { "${fmt(it, 1)}% ABV" },
        batch.stats().attenuation?.let { "${fmt(it * 100, 0)}% attenuation" },
        batch.mashEfficiency?.let { "${fmt(it * 100, 0)}% efficiency" },
    )
    if (headline.isNotEmpty()) {
        lines += ""
        lines += "<b>${headline.joinToString(" · ")}</b>"
    }

    val all = readings(recipe, batch, estimate)
    val logged = all.filter { it.actual != null }
    if (logged.isNotEmpty()) {
        val table = listOf("%-10s%7s%7s".format("", "Actual", "Plan")) +
            logged.map { "%-10s%7s%7s".format(it.label, fmt(it.actual, it.decimals), fmt(it.plan, it.decimals)) }
        lines += ""
        lines += "<pre>${table.joinToString("\n")}</pre>"
    }

    val missing = all.filter { it.actual == null }
    if (batch.packagedDate == null && missing.isNotEmpty()) {
        lines += ""
        lines += "Still to log: ${missing.joinToString(", ") { it.key }} → send <code>${missing.first().example}</code>"
    }

    batch.notes?.let {
        val notes = if (it.length > MAX_NOTES) it.take(MAX_NOTES) + "…" else it
        lines += ""
        lines += "<blockquote expandable>${notes.escapeHtml()}</blockquote>"
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
    lines += "Send readings as they come: <code>pre 27.5</code>, <code>og 1.052</code>, <code>post 23</code>, " +
        "<code>fermenter 21</code>, <code>fg 1.010</code>, <code>note …</code>"
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

fun batchLine(summary: BatchSummary, today: LocalDate) =
    "${batchStatus(summary.batch, today)} · ${summary.recipeName} #${summary.recipeNo}/${summary.batch.no} · ${friendlyDate(summary.batch.brewDate, today)}"
