package uk.beertool.telegram

import uk.beertool.batch.Batch
import uk.beertool.brewing.MashWater
import uk.beertool.common.fmt
import uk.beertool.common.formatAmount
import uk.beertool.recipe.MAX_MASH_PH
import uk.beertool.recipe.MIN_MASH_PH
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.WaterDose
import uk.beertool.recipe.boilAdditions
import uk.beertool.recipe.describe
import uk.beertool.recipe.doses
import uk.beertool.recipe.mashWater
import uk.beertool.recipe.stats
import uk.beertool.recipe.waterReport
import uk.beertool.user.BrewerPreferences
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

class BrewPanel(val text: String, val keyboard: InlineKeyboardMarkup)

private class StageView(val lines: List<String>, val rows: List<List<InlineKeyboardButton>> = emptyList())

private const val BAR_CELLS = 10

private fun progressBar(done: Double): String {
    val filled = (done.coerceIn(0.0, 1.0) * BAR_CELLS).roundToInt()
    return "▓".repeat(filled) + "░".repeat(BAR_CELLS - filled)
}

private fun countdown(timer: BrewTimer, totalMinutes: Int, now: Instant, suffix: String): String {
    val left = timer.minutesLeft(now)
    val done = if (totalMinutes <= 0) 1.0 else 1.0 - left.toDouble() / totalMinutes
    return "${progressBar(done)} $left min $suffix"
}

fun brewPanel(
    recipe: Recipe,
    batch: Batch,
    session: BrewSession,
    prefs: BrewerPreferences,
    now: Instant,
    today: LocalDate,
    publicUrl: String? = null,
    flash: String? = null,
): BrewPanel {
    val ref = "${recipe.no}:${batch.no}"
    val water = recipe.mashWater(prefs.mashThicknessLPerKg, prefs.grainAbsorptionLPerKg)
    val all = readings(recipe, batch, recipe.stats(prefs.whirlpool)).associateBy { it.key }

    val view = when (session.stage) {
        BrewDayStage.WATER -> waterStage(recipe, batch, prefs, water)
        BrewDayStage.MASH -> mashStage(recipe, session, now, water?.spargeVolumeL, all.getValue("pre"), ref)
        BrewDayStage.BOIL -> boilStage(recipe, session, now, all.getValue("pre"), ref)
        BrewDayStage.CHILL -> chillStage(recipe, listOf("post", "og", "fermenter").map(all::getValue), ref)
        BrewDayStage.FERMENT -> fermentStage(batch, today, listOf("og", "fg").map(all::getValue), ref)
    }

    val lines = mutableListOf<String>()
    flash?.let { lines += it; lines += "" }
    lines += title("🔥", batchName(recipe, batch), "/recipes/${recipe.no}/batches/${batch.no}/brew", publicUrl)
    lines += BrewDayStage.entries.joinToString(" › ") { if (it == session.stage) "<b>${it.label}</b>" else it.label }
    timerElsewhere(session, now)?.let { lines += it }
    lines += ""
    lines += view.lines
    if (session.stage <= BrewDayStage.MASH) planQuote(recipe)?.let { lines += ""; lines += it }

    val nav = listOfNotNull(
        session.stage.previous?.let { button("◀ ${it.label}", "st:$ref:${it.name}") },
        button("📝 Note", "ask:$ref:$NOTE_KEY"),
        session.stage.next?.let { button("${it.label} ▶", "st:$ref:${it.name}") },
    )
    val cancel = listOf(button("✖ Cancel brew", "cx:$ref")).takeIf { batch.isOnBrewDay }.orEmpty()
    return BrewPanel(clip(lines), keyboard(*view.rows.toTypedArray(), nav, cancel))
}

// Only a brew with no OG yet can be cancelled here; past that it is a real batch and belongs to the website.
fun cancelConfirmation(recipe: Recipe, batch: Batch, session: BrewSession): BrewPanel {
    val ref = "${recipe.no}:${batch.no}"
    val text = "🗑 <b>Cancel ${batchName(recipe, batch).escapeHtml()}?</b>\n<i>The batch is deleted with anything logged on it.</i>"
    return BrewPanel(
        text,
        keyboard(listOf(button("🗑 Yes, cancel it", "cxy:$ref"), button("↩ Keep brewing", "st:$ref:${session.stage.name}"))),
    )
}

// A timer keeps running while the brewer looks at another stage, so it stays in sight.
private fun timerElsewhere(session: BrewSession, now: Instant): String? {
    val timer = session.timer ?: return null
    return when (timer.kind) {
        TimerKind.MASH -> "⏱ Mash step ${session.mashStep + 1} · ${timer.minutesLeft(now)} min left".takeIf { session.stage != BrewDayStage.MASH }
        TimerKind.BOIL -> "⏱ Boil · ${timer.minutesLeft(now)} min to flame-out".takeIf { session.stage != BrewDayStage.BOIL }
    }
}

private fun planQuote(recipe: Recipe): String? {
    val mash = recipe.mashSteps.takeIf { it.isNotEmpty() }
        ?.joinToString(" → ", prefix = "Mash ") { "${formatAmount(it.tempC)} °C ${it.timeMin} min" }
    val additions = recipe.boilAdditions().takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ": ") { additionLine(it) }
    val boil = "Boil ${recipe.boilTimeMin} min${additions.orEmpty()}"
    return "<blockquote expandable>📋 Plan\n${listOfNotNull(mash, boil).joinToString("\n")}</blockquote>"
}

private fun waterStage(recipe: Recipe, batch: Batch, prefs: BrewerPreferences, mash: MashWater?): StageView {
    val treatment = batch.water
    val report = treatment?.let { recipe.waterReport(it, prefs) }
    val doses = if (treatment != null && report != null) report.doses(treatment.water) else emptyList()
    val lines = mutableListOf<String>()

    val strikeDoses = doses.filter { it.strike > 0.0 }
    if (mash != null || strikeDoses.isNotEmpty()) {
        lines += "<b>Strike</b>" + (mash?.let { " ${fmt(it.strikeVolumeL, 1)} L at ${fmt(it.strikeTempC, 1)} °C" } ?: "")
        strikeDoses.forEach { lines += "• " + doseLine(it, it.strike, treatment!!.water) + if (it.mashOnly) ", into the mash" else "" }
    }
    val spargeL = mash?.spargeVolumeL?.takeIf { it > 0.0 }
    val spargeDoses = doses.filter { !it.mashOnly && it.sparge > 0.0 }
    if (spargeL != null || spargeDoses.isNotEmpty()) {
        lines += "<b>Sparge</b>" + (spargeL?.let { " ${fmt(it, 1)} L" } ?: "")
        spargeDoses.forEach { lines += "• " + doseLine(it, it.sparge, treatment!!.water) }
    }
    val footer = listOfNotNull(
        treatment?.water?.describe()?.escapeHtml(),
        report?.mashPh?.let { ph ->
            "mash pH about ${fmt(ph, 2)}" + if (ph in MIN_MASH_PH..MAX_MASH_PH) "" else " ⚠️ aim for $MIN_MASH_PH-$MAX_MASH_PH"
        },
    )
    if (footer.isNotEmpty()) lines += "<i>${footer.joinToString(" · ")}</i>"
    if (lines.isEmpty()) lines += "No water plan on this recipe."
    return StageView(lines)
}

private fun doseLine(dose: WaterDose, amount: Double, water: RecipeWater) =
    "${dose.addition.labelIn(water)} ${fmt(amount, 1)} ${dose.addition.unit}"

private fun mashStage(recipe: Recipe, session: BrewSession, now: Instant, spargeL: Double?, preBoil: Reading, ref: String): StageView {
    val steps = recipe.mashSteps
    if (steps.isEmpty()) return StageView(listOf("No mash steps on this recipe."))
    val running = session.timer?.takeIf { it.kind == TimerKind.MASH }
    val lines = steps.mapIndexed { i, step ->
        val what = "${formatAmount(step.tempC)} °C · ${step.timeMin} min"
        when {
            i < session.mashStep -> "✓ $what"
            i == session.mashStep && running != null -> "⏱ <b>$what</b>\n${countdown(running, step.timeMin, now, "left")}"
            i == session.mashStep -> "▸ <b>$what</b>"
            else -> "◦ $what"
        }
    }.toMutableList()
    lines += ""

    val next = steps.getOrNull(session.mashStep)
    return when {
        running != null -> {
            lines += "<i>I'll message you when the step is done.</i>"
            StageView(lines, listOf(listOf(button("⏭ End step now", "mend:$ref"), button("⏹ Stop", "stop:$ref"))))
        }
        next != null -> {
            lines += "<i>Start each step once the mash is at temperature.</i>"
            StageView(lines, listOf(listOf(button("▶ At ${formatAmount(next.tempC)} °C · start ${next.timeMin} min", "mash:$ref"))))
        }
        else -> {
            lines += "✅ Mash done." + (spargeL?.takeIf { it > 0.0 }?.let { " Sparge with ${fmt(it, 1)} L." } ?: "")
            lines += readingLine(preBoil)
            StageView(lines, listOf(listOf(askButton(preBoil, ref))))
        }
    }
}

private fun boilStage(recipe: Recipe, session: BrewSession, now: Instant, preBoil: Reading, ref: String): StageView {
    val running = session.timer?.takeIf { it.kind == TimerKind.BOIL }
    val lines = mutableListOf("<b>${recipe.boilTimeMin} min boil</b>")
    running?.let { lines += countdown(it, recipe.boilTimeMin, now, "to flame-out") }
    val additions = recipe.boilAdditions()
    if (additions.isEmpty()) lines += "No additions."
    additions.forEach { addition ->
        val mark = when {
            running == null -> "•"
            addition.minutesBeforeEnd >= running.announcedDownTo -> "✓"
            else -> "◦"
        }
        lines += "$mark ${additionLine(addition)}"
    }
    lines += ""
    lines += readingLine(preBoil)
    val timer = if (running == null) button("⏱ Start boil timer", "boil:$ref") else button("⏹ Stop boil timer", "stop:$ref")
    return StageView(lines, listOf(listOf(askButton(preBoil, ref), timer)))
}

private fun chillStage(recipe: Recipe, toLog: List<Reading>, ref: String): StageView {
    val lines = mutableListOf<String>()
    if (recipe.yeasts.isEmpty()) lines += "Chill to pitching temperature and transfer to the fermenter."
    else {
        lines += "Chill to pitching temperature, transfer to the fermenter and pitch:"
        recipe.yeasts.forEach { lines += "• ${it.name.escapeHtml()}" }
    }
    lines += ""
    lines += toLog.map(::readingLine)
    return StageView(lines, listOf(toLog.map { askButton(it, ref) }))
}

private fun fermentStage(batch: Batch, today: LocalDate, toLog: List<Reading>, ref: String): StageView {
    val lines = mutableListOf("<i>${batchProgress(batch, today).escapeHtml()}</i>")
    batch.abv?.let { lines += "<b>${fmt(it, 1)}% ABV</b>" }
    lines += toLog.map(::readingLine)
    val fg = toLog.single { it.key == "fg" }
    val actions = listOfNotNull(
        askButton(fg, ref),
        button("🍺 Mark packaged", "pkg:$ref").takeIf { batch.measuredFg != null && !batch.isPackaged },
    )
    return StageView(lines, listOf(actions))
}

private fun Reading.title() = label.replaceFirstChar { it.uppercase() }

private fun readingLine(reading: Reading) =
    if (reading.actual != null) reading.text().replaceFirstChar { it.uppercase() }
    else "${reading.title()} <i>to log</i>" + (reading.plan?.let { " · plan ${reading.show(it)}${reading.unit}" } ?: "")

private fun askButton(reading: Reading, ref: String) =
    button("${if (reading.actual == null) "📏" else "✓"} ${reading.title()}", "ask:$ref:${reading.key}")

class ReadingPrompt(val text: String, val placeholder: String)

fun readingPrompt(key: String, recipe: Recipe, batch: Batch, estimate: RecipeStats): ReadingPrompt? {
    if (key == NOTE_KEY) return ReadingPrompt("📝 <b>Note</b>\n<i>What should I write down?</i>", "e.g. mash pH 5.3")
    val reading = readings(recipe, batch, estimate).find { it.key == key } ?: return null
    val plan = reading.plan?.let(reading.show)
    val text = "📏 <b>${reading.question}?</b>" + (plan?.let { "\n<i>Plan $it${reading.unit}</i>" } ?: "")
    return ReadingPrompt(text, plan ?: reading.example.substringAfter(' '))
}

fun mashStepDoneText(recipe: Recipe, step: Int, spargeL: Double?): String {
    val name = recipeName(recipe).escapeHtml()
    val next = recipe.mashSteps.getOrNull(step + 1)
        ?: return "✅ <b>Mash done</b> · $name\n" +
            (spargeL?.takeIf { it > 0.0 }?.let { "Sparge with ${fmt(it, 1)} L, then log the pre-boil volume." } ?: "Sparge, then log the pre-boil volume.")
    return "⏱ <b>Step ${step + 1} done</b> · $name\nRaise the mash to <b>${formatAmount(next.tempC)} °C</b>, then start the next step."
}
