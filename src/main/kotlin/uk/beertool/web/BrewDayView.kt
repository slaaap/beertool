package uk.beertool.web

import kotlinx.html.*
import uk.beertool.batch.Batch
import uk.beertool.batch.BrewDayStage
import uk.beertool.brewing.Calculators
import uk.beertool.common.fmt
import uk.beertool.recipe.DEFAULT_GRAIN_TEMP_C
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.boilAdditions
import uk.beertool.recipe.mashGrainKg
import uk.beertool.recipe.mashWater
import uk.beertool.user.User
import java.time.LocalDate

private const val READINGS_FORM = "readings"

fun HTML.brewDayPage(user: User, batch: Batch, recipe: Recipe, estimate: RecipeStats, saved: Boolean) {
    page("Brew day · ${recipe.name} #${batch.no}", user) {
        div("brewday") {
            id = "brewday"
            attributes["data-key"] = batch.id.toString()
            attributes["data-start"] = (if (batch.isOnBrewDay) BrewDayStage.WATER else BrewDayStage.FERMENT).key
            div("bd-head") {
                div("bd-title") {
                    a(href = "/recipes/${recipe.no}/batches/${batch.no}", classes = "bd-back") {
                        title = "Back to the brew"
                        attributes["aria-label"] = title
                        iconSvg(Icon.BACK)
                    }
                    h1 {
                        +recipe.name
                        span("title-num") { +" #${batch.no}" }
                    }
                    button(type = ButtonType.button, classes = "wake") { id = "wake"; hidden = true; +"Screen on" }
                }
                nav("stage-bar") {
                    BrewDayStage.entries.forEach { stage ->
                        a(href = "#${stage.key}") {
                            attributes["data-tab"] = stage.key
                            span { +stage.label }
                            span("tab-clock") {}
                        }
                    }
                }
                if (saved) span("saved-note") { id = "saved-note"; +"Saved ✓" }
            }

            val readings = ReadingInputs(recipe, batch, estimate)
            stage(BrewDayStage.WATER) {
                if (recipe.mashSteps.isNotEmpty()) mashWaterGuide(recipe, user)
                brewDayWater(recipe, batch, user.preferences)
            }
            stage(BrewDayStage.MASH) {
                if (recipe.mashSteps.isEmpty()) p("muted") { +"No mash schedule on this recipe." }
                else mashTimer(recipe, batch)
                readingsRow(readings.preBoil)
            }
            stage(BrewDayStage.BOIL) {
                boilTimer(recipe, batch)
                readingsRow(readings.postBoil)
            }
            stage(BrewDayStage.CHILL) {
                p {
                    +"Chill to pitching temperature and transfer to the fermenter."
                    if (recipe.yeasts.isNotEmpty()) +" Pitch:"
                }
                if (recipe.yeasts.isNotEmpty()) ul("pitch") { recipe.yeasts.forEach { li { +it.name } } }
                readingsRow(readings.fermenter, readings.og)
            }
            stage(BrewDayStage.FERMENT) {
                batch.measuredOg?.let { og ->
                    div("stats duo") {
                        statTile("OG", fmt(og, 3), null)
                        statTile("ABV", fmt(batch.abv, 1), "%")
                    }
                }
                readingsRow(readings.fg)
                field("Notes") { textArea { name = "notes"; attributes["form"] = READINGS_FORM; +(batch.notes ?: "") } }
                saveButton()
            }
            div("stage-nav") {
                button(type = ButtonType.button, classes = "btn") { id = "stage-prev"; hidden = true }
                button(type = ButtonType.button, classes = "btn") { id = "stage-next"; hidden = true }
            }
            form(action = "/recipes/${recipe.no}/batches/${batch.no}/brew", method = FormMethod.post) {
                id = READINGS_FORM
                hiddenInput(name = "brewDate") { value = batch.brewDate?.toString() ?: LocalDate.now().toString() }
                hiddenInput(name = "packagedDate") { value = batch.packagedDate?.toString() ?: "" }
            }
        }

        js("brew-day.js")
        js("water.js")
        js("ui.js")
    }
}

private val BrewDayStage.key get() = name.lowercase()

private fun FlowContent.stage(stage: BrewDayStage, block: DIV.() -> Unit) =
    div("stage") {
        attributes["data-stage"] = stage.key
        h2("stage-title") { +stage.label }
        block()
    }

// Each stage shows only its own readings, but they all belong to the one form so a save keeps the rest.
private fun FlowContent.readingsRow(vararg inputs: ReadingInput) {
    div("stage-readings") {
        fieldGrid(columns = inputs.size) { inputs.forEach { readingField(it) { attributes["form"] = READINGS_FORM } } }
        if (inputs.none { it.name == "measuredFg" }) saveButton()
    }
}

private fun FlowContent.saveButton() =
    submitInput(classes = "btn primary") { attributes["form"] = READINGS_FORM; value = "Save" }

private fun FlowContent.mashTimer(recipe: Recipe, batch: Batch) {
    div("timer-card") {
        val first = recipe.mashSteps.first()
        div("timer-clock") {
            id = "mash-clock"
            attributes["data-key"] = batch.id.toString()
            +clock(first.timeMin)
        }
        p("timer-status") { id = "mash-status"; +"Bring the mash to ${fmt(first.tempC, 1)} °C, then start." }
        div("timer-controls") {
            button(type = ButtonType.button, classes = "btn primary") { id = "mash-start"; +"Start" }
            button(type = ButtonType.button, classes = "btn") { id = "mash-next"; +"End step" }
            button(type = ButtonType.button, classes = "btn") { id = "mash-reset"; +"Reset" }
        }
        table("mash-plan") {
            thead { tr { th { +"Rest" }; th(classes = "num") { +"Temp (°C)" }; th(classes = "num") { +"Time (min)" } } }
            tbody {
                recipe.mashSteps.forEach { s ->
                    tr {
                        attributes["data-temp"] = fmt(s.tempC, 1)
                        attributes["data-min"] = s.timeMin.toString()
                        td("step") {}
                        td("num") { +fmt(s.tempC, 1) }
                        td("num") { +s.timeMin.toString() }
                    }
                }
            }
        }
    }
}

private fun FlowContent.boilTimer(recipe: Recipe, batch: Batch) {
    div("timer-card") {
        div("timer-clock") {
            id = "boil-clock"
            attributes["data-boil"] = recipe.boilTimeMin.toString()
            attributes["data-key"] = batch.id.toString()
            +clock(recipe.boilTimeMin)
        }
        p("timer-status") { id = "boil-status" }
        div("timer-controls") {
            button(type = ButtonType.button, classes = "btn primary") { id = "boil-start"; +"Start" }
            button(type = ButtonType.button, classes = "btn") { id = "boil-reset"; +"Reset" }
        }
        val additions = recipe.boilAdditions()
        if (additions.isEmpty()) {
            p("muted") { +"No boil additions on this recipe." }
        } else {
            ul("boil-adds") {
                id = "boil-adds"
                additions.forEach { a ->
                    li {
                        attributes["data-remaining"] = a.minutesBeforeEnd.toString()
                        span("at") { +(if (a.minutesBeforeEnd == 0) "flame-out" else "${a.minutesBeforeEnd} min") }
                        span("what") { +a.what }
                    }
                }
            }
        }
    }
}

private fun FlowContent.mashWaterGuide(recipe: Recipe, user: User) {
    val prefs = user.preferences
    val water = recipe.mashWater(prefs.mashThicknessLPerKg, prefs.grainAbsorptionLPerKg) ?: return
    val grainKg = recipe.mashGrainKg()
    val preBoil = recipe.preBoilVolumeL ?: return
    val firstRest = recipe.mashSteps.first().tempC
    div("mash-water") {
        id = "mash-water"
        attributes["data-grain"] = fmt(grainKg, 3)
        attributes["data-preboil"] = fmt(preBoil, 2)
        attributes["data-rest"] = fmt(firstRest, 1)
        attributes["data-heat"] = Calculators.GRAIN_WATER_HEAT_RATIO.toString()
        div("stats trio") {
            statTile("Strike", fmt(water.strikeVolumeL, 1), "L", valueId = "strike-water")
            statTile("Strike temp", fmt(water.strikeTempC, 1), "°C", accent = true, valueId = "strike-temp")
            statTile("Sparge", fmt(water.spargeVolumeL, 1), "L", valueId = "sparge-water")
        }
        fieldGrid(columns = 3) {
            field("Grain (°C)") {
                numberInput { id = "mw-grain-temp"; value = fmt(DEFAULT_GRAIN_TEMP_C, 0); numAttrs("0.5") }
            }
            field("Ratio (L/kg)") {
                numberInput { id = "mw-ratio"; value = fmt(prefs.mashThicknessLPerKg, 1); numAttrs("0.1") }
            }
            field("Retention (L/kg)") {
                numberInput { id = "mw-retention"; value = fmt(prefs.grainAbsorptionLPerKg, 1); numAttrs("0.1") }
            }
        }
    }
}

private fun clock(min: Int) = "$min:00"
