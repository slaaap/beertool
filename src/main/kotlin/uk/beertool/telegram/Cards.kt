package uk.beertool.telegram

import uk.beertool.batch.Batch
import uk.beertool.batch.BatchSummary
import uk.beertool.batch.stats
import uk.beertool.recipe.BoilAddition
import uk.beertool.recipe.BrewStage
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.YeastUsage
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

// Titles link to the matching page on the site when its address is known; buttons are kept for actions.
private fun title(icon: String, text: String, path: String, publicUrl: String?): String {
    val name = text.escapeHtml()
    val linked = publicUrl?.let { "<a href=\"${"$it$path".escapeAttribute()}\">$name</a>" } ?: name
    return "$icon <b>$linked</b>"
}

private fun subtitle(text: String) = "<i>${text.escapeHtml()}</i>"

fun recipeName(recipe: Recipe) = "${recipe.name} #${recipe.no}"

fun batchName(recipe: Recipe, batch: Batch) = "${recipeName(recipe)} · batch ${batch.no}"

fun statsLine(stats: RecipeStats) = "${fmt(stats.abv, 1)}% ABV · ${fmt(stats.ibu, 0)} IBU · ${fmt(stats.colourEbc, 0)} EBC"

fun recipeCard(recipe: Recipe, stats: RecipeStats, publicUrl: String? = null): String {
    val lines = mutableListOf(title("📖", recipeName(recipe), "/recipes/${recipe.no}", publicUrl))
    recipe.style?.let { lines += subtitle(it) }
    lines += ""
    lines += "<b>${statsLine(stats)}</b>"
    lines += "OG ${fmt(stats.og, 3)} → FG ${fmt(stats.fg, 3)}"
    lines += "${num(recipe.fermenterVolumeL)} L · ${recipe.boilTimeMin} min boil · ${fmt(recipe.efficiency * 100, 0)}% efficiency"

    fun section(heading: String, items: List<String>) {
        if (items.isEmpty()) return
        lines += ""
        lines += bold(heading)
        items.forEach { lines += it }
    }
    section("Mash", mashLines(recipe))
    section("Fermentables", recipe.fermentables.inBrewOrder().map {
        "• ${num(it.amountKg)} kg ${it.name.escapeHtml()}" + if (it.usage == FermentableUsage.MASH) "" else " · ${stage(it.usage, it.boilTimeMin)}"
    })
    section("Hops", recipe.hops.inBrewOrder().map {
        val use = if (it.usage == HopUsage.DRY_HOP && it.dryHopDays != null) "dry hop ${days(it.dryHopDays.toLong())}" else stage(it.usage, it.boilTimeMin)
        "• ${num(it.amountG)} g ${it.name.escapeHtml()} ${num(it.alphaAcid)}% · $use"
    })
    section("Yeast", recipe.yeasts.inBrewOrder().map {
        "• ${it.name.escapeHtml()}" + if (it.usage == YeastUsage.PRIMARY) "" else " · ${stage(it.usage, null)}"
    })
    section("Extras", recipe.extras.inBrewOrder().map {
        "• ${num(it.amount)} ${it.unit.code} ${it.name.escapeHtml()} · ${stage(it.usage, it.boilTimeMin)}"
    })
    return clip(lines)
}

private fun mashLines(recipe: Recipe) = recipe.mashSteps.mapIndexed { i, step -> "${i + 1}. ${num(step.tempC)} °C · ${step.timeMin} min" }

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private const val MAX_NOTES = 1500

fun friendlyDate(date: LocalDate?, today: LocalDate): String = when {
    date == null -> MISSING
    date.year == today.year -> date.format(DAY_MONTH)
    else -> date.format(DAY_MONTH_YEAR)
}

internal fun String.escapeAttribute() = escapeHtml().replace("\"", "&quot;")

private fun days(n: Long) = if (n == 1L) "1 day" else "$n days"

private fun statusIcon(batch: Batch) = when {
    batch.packagedDate != null -> "🍺"
    batch.measuredOg == null -> "🔥"
    else -> "🫧"
}

private fun batchProgress(batch: Batch, today: LocalDate): String {
    fun day(date: LocalDate) = if (date == today) "today" else friendlyDate(date, today)
    val brewed = batch.brewDate
    val packaged = batch.packagedDate
    return when {
        batch.measuredOg == null && packaged == null -> "Brew day" + (brewed?.let { " · ${day(it)}" } ?: "")
        else -> listOfNotNull(
            brewed?.let { "Brewed ${day(it)}" },
            when {
                packaged != null -> "packaged ${day(packaged)}"
                brewed != null && brewed != today -> "fermenting, day ${ChronoUnit.DAYS.between(brewed, today)}"
                else -> "fermenting"
            },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

private class Reading(val label: String, val key: String, val example: String, val actual: Double?, val plan: Double?, val unit: String, val show: (Double) -> String)

private fun gravity(v: Double) = fmt(v, 3)

private fun volume(v: Double) = num(Math.round(v * 10) / 10.0)

private fun readings(recipe: Recipe, batch: Batch, estimate: RecipeStats) = listOf(
    Reading("OG", "og", "og 1.052", batch.measuredOg, estimate.og, "", ::gravity),
    Reading("FG", "fg", "fg 1.010", batch.measuredFg, estimate.fg, "", ::gravity),
    Reading("pre-boil", "pre", "pre 27.5", batch.measuredPreBoilVolumeL, recipe.preBoilVolumeL, " L", ::volume),
    Reading("post-boil", "post", "post 23", batch.measuredPostBoilVolumeL, recipe.postBoilVolumeL, " L", ::volume),
    Reading("fermenter", "fermenter", "fermenter 21", batch.measuredFermenterVolumeL, recipe.fermenterVolumeL, " L", ::volume),
)

// The plan is only worth reading where it differs from what was measured, as shown.
private fun Reading.text(): String {
    val measured = show(actual!!)
    val planned = plan?.let(show)
    return "$label $measured$unit" + if (planned != null && planned != measured) " (plan $planned$unit)" else ""
}

private fun readingsLine(readings: List<Reading>) =
    readings.filter { it.actual != null }.joinToString(" · ") { it.text() }.replaceFirstChar { it.uppercase() }.ifEmpty { null }

fun batchCard(recipe: Recipe, batch: Batch, estimate: RecipeStats, today: LocalDate, publicUrl: String? = null): String {
    val lines = mutableListOf(
        title(statusIcon(batch), batchName(recipe, batch), "/recipes/${recipe.no}/batches/${batch.no}", publicUrl),
        subtitle(batchProgress(batch, today)),
    )

    val headline = listOfNotNull(
        batch.abv?.let { "${fmt(it, 1)}% ABV" },
        batch.stats().attenuation?.let { "${fmt(it * 100, 0)}% attenuation" },
        batch.mashEfficiency?.let { "${fmt(it * 100, 0)}% efficiency" },
    )
    val all = readings(recipe, batch, estimate)
    val figures = listOfNotNull(readingsLine(all.take(2)), readingsLine(all.drop(2)))
    if (headline.isNotEmpty() || figures.isNotEmpty()) lines += ""
    if (headline.isNotEmpty()) lines += "<b>${headline.joinToString(" · ")}</b>"
    lines += figures

    val missing = all.filter { it.actual == null }
    if (batch.packagedDate == null && missing.isNotEmpty()) {
        lines += ""
        lines += "Still to log: ${missing.joinToString(", ") { it.key }}. Send e.g. <code>${missing.first().example}</code>"
    }

    batch.notes?.let {
        val notes = if (it.length > MAX_NOTES) it.take(MAX_NOTES) + "…" else it
        lines += ""
        lines += "<blockquote expandable>${notes.escapeHtml()}</blockquote>"
    }
    return clip(lines)
}

const val READINGS_HINT =
    "<code>pre 27.5</code>, <code>og 1.052</code>, <code>post 23</code>, <code>fermenter 21</code>, <code>fg 1.010</code>, <code>note …</code>"

fun brewDayCard(recipe: Recipe, batch: Batch, prefs: BrewerPreferences, today: LocalDate, publicUrl: String? = null): String {
    val lines = mutableListOf(
        title("🔥", "Brew day · ${batchName(recipe, batch)}", "/recipes/${recipe.no}/batches/${batch.no}/brew", publicUrl),
        subtitle((batch.brewDate ?: today).let { if (it == today) "Today" else friendlyDate(it, today) }),
    )
    recipe.mashWater(prefs.mashThicknessLPerKg, prefs.grainAbsorptionLPerKg)?.let {
        lines += ""
        lines += bold("Water")
        lines += "Strike ${fmt(it.strikeVolumeL, 1)} L at ${fmt(it.strikeTempC, 1)} °C"
        lines += "Sparge ${fmt(it.spargeVolumeL, 1)} L"
    }
    if (recipe.mashSteps.isNotEmpty()) {
        lines += ""
        lines += bold("Mash")
        lines += mashLines(recipe)
    }
    lines += ""
    lines += bold("Boil · ${recipe.boilTimeMin} min")
    val additions = recipe.boilAdditions()
    if (additions.isEmpty()) lines += "No additions."
    additions.forEach { lines += "• ${additionLine(it)}" }
    lines += ""
    lines += "<i>Send readings as they come:</i> $READINGS_HINT"
    return clip(lines)
}

fun additionLine(addition: BoilAddition) =
    (if (addition.minutesBeforeEnd == 0) "flame-out" else "${addition.minutesBeforeEnd} min") + " · ${addition.what.escapeHtml()}"

fun boilAlertText(recipe: Recipe, alert: BoilAlert, minutesLeft: Long): String {
    val lines = mutableListOf<String>()
    val due = alert.additions.filter { it.minutesBeforeEnd > 0 }
    if (due.isNotEmpty()) {
        lines += "⏱ <b>Add now</b> · ${recipeName(recipe).escapeHtml()}"
        due.forEach { lines += "• ${it.what.escapeHtml()}" }
        if (!alert.flameOut) lines += subtitle("$minutesLeft min to flame-out")
    }
    if (alert.flameOut) {
        if (lines.isNotEmpty()) lines += ""
        lines += "🔥 <b>Flame-out</b> · ${recipeName(recipe).escapeHtml()}"
        alert.additions.filter { it.minutesBeforeEnd == 0 }.forEach { lines += "• ${it.what.escapeHtml()}" }
        lines += ""
        lines += "<i>Once it's chilled and in the fermenter, send</i> <code>post 23 og 1.052 fermenter 21</code>"
    }
    return clip(lines)
}

fun readingsSaved(readings: Readings): String {
    val parts = listOfNotNull(
        readings.og?.let { "OG ${fmt(it, 3)}" },
        readings.fg?.let { "FG ${fmt(it, 3)}" },
        readings.preBoilL?.let { "pre-boil ${num(it)} L" },
        readings.postBoilL?.let { "post-boil ${num(it)} L" },
        readings.fermenterL?.let { "fermenter ${num(it)} L" },
        readings.note?.let { "a note" },
    )
    return "✅ Saved ${parts.joinToString(", ")}"
}

fun recipeLine(summary: RecipeSummary) = summary.name + (summary.style?.let { " · $it" } ?: "")

fun batchLine(summary: BatchSummary, today: LocalDate) =
    "${friendlyDate(summary.batch.brewDate, today)} · ${summary.recipeName}${activeMark(summary.batch)}"

// List rows read like a diary; a dot marks the brews still going among the packaged majority.
fun activeMark(batch: Batch) = if (batch.packagedDate == null) " •" else ""
