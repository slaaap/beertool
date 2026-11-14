package uk.beertool.web

import kotlinx.html.*
import uk.beertool.batch.Batch
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

fun HTML.brewDayPage(user: User, batch: Batch, recipe: Recipe, estimate: RecipeStats, saved: Boolean) {
    page("Brew day · ${recipe.name} #${batch.no}", user) {
        div("brewday") {
            h1 {
                +recipe.name
                span("title-num") { +" #${batch.no}" }
            }
            p("muted") { +"Brew day · ${fmt(batch.brewDate)}" }

            brewDayWater(recipe, batch, user.preferences)

            sectionHead("Mash")
            if (recipe.mashSteps.isEmpty()) {
                p("muted") { +"No mash schedule on this recipe." }
            } else {
                mashWaterGuide(recipe, user)
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

            sectionHead("Boil")
            div("timer-card") {
                div("timer-clock") {
                    id = "boil-clock"
                    attributes["data-boil"] = recipe.boilTimeMin.toString()
                    attributes["data-key"] = batch.id.toString()
                    +clock(recipe.boilTimeMin)
                }
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

            sectionHead("Readings")
            if (saved) span("saved-note") { id = "saved-note"; +"Saved ✓" }
            form(action = "/recipes/${recipe.no}/batches/${batch.no}/brew", method = FormMethod.post) {
                hiddenInput(name = "brewDate") { value = batch.brewDate?.toString() ?: LocalDate.now().toString() }
                hiddenInput(name = "packagedDate") { value = batch.packagedDate?.toString() ?: "" }
                val readings = ReadingInputs(recipe, batch, estimate)
                fieldGrid(columns = 3) {
                    readingField(readings.preBoil)
                    readingField(readings.postBoil)
                    readingField(readings.fermenter)
                }
                fieldGrid(columns = 2) {
                    readingField(readings.og)
                    readingField(readings.fg)
                }
                fieldGrid(columns = 1) { field("Notes") { textArea { name = "notes"; +(batch.notes ?: "") } } }
                actionBar {
                    submitInput(classes = "btn primary") { value = "Save readings" }
                    btnLink("/recipes/${recipe.no}/batches/${batch.no}", "Back", Icon.BACK)
                }
            }
        }

        js("brew-day.js")
        js("water.js")
        js("ui.js")
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
