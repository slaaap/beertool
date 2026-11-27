package uk.beertool.web

import kotlinx.html.*
import uk.beertool.batch.Batch
import uk.beertool.brewhouse.BREWHOUSE_WEB_PATH
import uk.beertool.brewhouse.BrewhouseProblem
import uk.beertool.brewhouse.Phase
import uk.beertool.brewhouse.Schedule
import uk.beertool.brewhouse.brewhouseSchedule
import uk.beertool.common.fmt
import uk.beertool.recipe.Recipe
import uk.beertool.user.User

private class PhaseButton(val command: String, val label: String, val phases: Set<Phase>, val primary: Boolean = false)

private val RUNNING = setOf(Phase.STRIKE, Phase.DOUGH_IN, Phase.MASH, Phase.MASHED, Phase.BOIL_HEAT, Phase.BOIL)

private val PHASE_BUTTONS = listOf(
    PhaseButton("dough_in", "Grain is in", setOf(Phase.DOUGH_IN), primary = true),
    PhaseButton("start_boil", "Start boil", setOf(Phase.MASHED), primary = true),
    PhaseButton("boiling", "It's boiling", setOf(Phase.BOIL_HEAT), primary = true),
    PhaseButton("pause", "Pause", RUNNING),
    PhaseButton("resume", "Resume", setOf(Phase.PAUSED, Phase.MANUAL, Phase.FAULT), primary = true),
    PhaseButton("stop", "Stop", RUNNING + Phase.PAUSED + Phase.MANUAL + Phase.FAULT),
)

// The page script fills this from the live status; the server only lays it out and renders the schedule a start sends.
internal fun FlowContent.brewhousePanel(recipe: Recipe, batch: Batch, user: User) {
    val (schedule, problem) = try {
        brewhouseSchedule(batch.id, recipe, user.preferences) to null
    } catch (e: BrewhouseProblem) {
        null to e.message
    }
    div("bh-panel") {
        id = "bh"
        attributes["data-batch"] = batch.id.toString()
        attributes["data-base"] = BREWHOUSE_WEB_PATH
        div("bh-conn") {
            span("bh-state") { id = "bh-state"; +"Brewhouse: checking…" }
            span("bh-why") { id = "bh-why"; hidden = true }
        }
        iframe(classes = "bh-widget") {
            id = "bh-widget"; hidden = true
            title = "Brewhouse devices"
            attributes["sandbox"] = "allow-scripts"
            attributes["referrerpolicy"] = "no-referrer"
        }
        button(type = ButtonType.button, classes = "notice bh-sound") { id = "bh-sound"; hidden = true; +"Tap here to turn on alarm sounds" }
        p("alert") { id = "bh-fault"; hidden = true }
        div("notice bh-manual") {
            id = "bh-manual"; hidden = true
            span { id = "bh-note" }
        }
        div {
            id = "bh-start"; hidden = true
            if (schedule == null) {
                p("muted") { +(problem ?: "This brew can't run on the brewhouse.") }
            } else {
                button(type = ButtonType.button, classes = "btn primary bh-wide") { id = "bh-start-btn"; disabled = true; +"Start on brewhouse" }
                div("bh-preview") {
                    id = "bh-preview"; hidden = true
                    schedulePreview(schedule)
                    div("timer-controls") {
                        button(type = ButtonType.button, classes = "btn primary") { id = "bh-confirm"; +"Start now" }
                        button(type = ButtonType.button, classes = "btn") { id = "bh-cancel"; +"Cancel" }
                    }
                }
            }
        }
        div {
            id = "bh-live"; hidden = true
            p("bh-phase") { id = "bh-phase" }
            div("bh-temp") {
                span("bh-now") { id = "bh-temp"; +"-" }
                span("bh-unit") { +"°C" }
                span("bh-target") { id = "bh-target" }
            }
            div("stats auto bh-stats") {
                statTile("Heat", "-", "%", valueId = "bh-heat")
                statTile("Step left", "-", "min", valueId = "bh-step-left")
                statTile("ETA", "-", null, valueId = "bh-eta")
                statTile("Boil left", "-", "min", valueId = "bh-boil-left")
            }
            div("timer-controls bh-actions") {
                PHASE_BUTTONS.forEach { b ->
                    button(type = ButtonType.button, classes = if (b.primary) "btn primary" else "btn") {
                        attributes["data-command"] = b.command
                        attributes["data-phases"] = b.phases.joinToString(" ") { it.name.lowercase() }
                        hidden = true
                        +b.label
                    }
                }
            }
        }
        p("alert") { id = "bh-error"; hidden = true }
        div("bh-chart") { id = "bh-chart"; hidden = true }
    }
}

private fun FlowContent.schedulePreview(schedule: Schedule) {
    div("stats trio") {
        statTile("Strike", fmt(schedule.waterL, 1), "L", valueId = "bh-pv-water")
        statTile("Strike temp", fmt(schedule.strikeC, 1), "°C", accent = true, valueId = "bh-pv-strike")
        statTile("Boil", schedule.boil?.let { "${it.minutes}" } ?: "none", schedule.boil?.let { "min" })
    }
    table("mash-plan") {
        thead { tr { th { +"Step" }; th(classes = "num") { +"Temp (°C)" }; th(classes = "num") { +"Time (min)" } } }
        tbody {
            schedule.steps.forEachIndexed { i, s ->
                tr {
                    td { +"${i + 1}" }
                    td("num") { +fmt(s.tempC, 1) }
                    td("num") { +s.minutes.toString() }
                }
            }
        }
    }
    schedule.boil?.let { p("muted") { +"Then boil ${fmt(it.volumeL, 1)} L for ${it.minutes} min." } }
}
