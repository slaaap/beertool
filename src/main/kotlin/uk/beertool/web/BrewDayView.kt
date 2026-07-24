package uk.beertool.web

import kotlinx.html.*
import uk.beertool.batch.Batch
import uk.beertool.brewing.Calculators
import uk.beertool.recipe.DEFAULT_GRAIN_TEMP_C
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.boilAdditions
import uk.beertool.recipe.mashGrainKg
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

            sectionHead("Mash")
            if (recipe.mashSteps.isEmpty()) {
                p("muted") { +"No mash schedule on this recipe." }
            } else {
                mashWaterGuide(recipe, user)
                table("mash-plan") {
                    thead { tr { th { +"Rest" }; th(classes = "num") { +"Temp (°C)" }; th(classes = "num") { +"Time (min)" } } }
                    tbody {
                        recipe.mashSteps.forEach { s ->
                            tr {
                                td("step") {}
                                td("num") { +fmt(s.tempC, 1) }
                                td("num") { +s.timeMin.toString() }
                            }
                        }
                    }
                }
            }

            sectionHead("Boil")
            div("boil-clock") {
                id = "boil-clock"
                attributes["data-boil"] = recipe.boilTimeMin.toString()
                attributes["data-key"] = batch.id.toString()
                +boilClock(recipe.boilTimeMin)
            }
            div("boil-controls") {
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

            sectionHead("Readings")
            if (saved) span("saved-note") { id = "saved-note"; +"Saved ✓" }
            form(action = "/recipes/${recipe.no}/batches/${batch.no}/brew", method = FormMethod.post) {
                hiddenInput(name = "brewDate") { value = batch.brewDate?.toString() ?: LocalDate.now().toString() }
                hiddenInput(name = "packagedDate") { value = batch.packagedDate?.toString() ?: "" }
                fieldGrid(columns = 2) {
                    field("Pre-boil (L)") {
                        numberInput(name = "measuredPreBoilVolumeL") {
                            value = batch.measuredPreBoilVolumeL?.toString() ?: ""
                            placeholder = recipe.preBoilVolumeL?.let { fmt(it, 1) } ?: ""; numAttrs("0.5")
                        }
                    }
                    field("Post-boil (L)") {
                        numberInput(name = "measuredPostBoilVolumeL") {
                            value = batch.measuredPostBoilVolumeL?.toString() ?: ""
                            placeholder = fmt(recipe.postBoilVolumeL, 1); numAttrs("0.5")
                        }
                    }
                    field("OG") {
                        numberInput(name = "measuredOg") {
                            value = batch.measuredOg?.toString() ?: ""; placeholder = fmt(estimate.og, 3); numAttrs("0.001")
                        }
                    }
                    field("Fermenter (L)") {
                        numberInput(name = "measuredFermenterVolumeL") {
                            value = batch.measuredFermenterVolumeL?.toString() ?: ""
                            placeholder = fmt(recipe.fermenterVolumeL, 1); numAttrs("0.5")
                        }
                    }
                    field("FG") {
                        numberInput(name = "measuredFg") {
                            value = batch.measuredFg?.toString() ?: ""; placeholder = fmt(estimate.fg, 3); numAttrs("0.001")
                        }
                    }
                }
                fieldGrid(columns = 1) { field("Notes") { textArea { name = "notes"; +(batch.notes ?: "") } } }
                actionBar {
                    submitInput(classes = "btn primary") { value = "Save readings" }
                    btnLink("/recipes/${recipe.no}/batches/${batch.no}", "Back", Icon.BACK)
                }
            }
        }

        js("brew-day.js")
        js("ui.js")
    }
}

private fun FlowContent.mashWaterGuide(recipe: Recipe, user: User) {
    val grainKg = recipe.mashGrainKg()
    val preBoil = recipe.preBoilVolumeL
    val firstRest = recipe.mashSteps.first().tempC
    if (grainKg <= 0.0 || preBoil == null) return
    val prefs = user.preferences
    val water = Calculators.mashWater(
        grainKg = grainKg,
        firstRestTempC = firstRest,
        grainTempC = DEFAULT_GRAIN_TEMP_C,
        preBoilVolumeL = preBoil,
        thicknessLPerKg = prefs.mashThicknessLPerKg,
        absorptionLPerKg = prefs.grainAbsorptionLPerKg,
    )
    div("mash-water") {
        id = "mash-water"
        attributes["data-grain"] = fmt(grainKg, 3)
        attributes["data-preboil"] = fmt(preBoil, 2)
        attributes["data-rest"] = fmt(firstRest, 1)
        attributes["data-heat"] = Calculators.GRAIN_WATER_HEAT_RATIO.toString()
        div("stats trio") {
            statTile("Strike water", fmt(water.strikeVolumeL, 1), "L", valueId = "strike-water")
            statTile("Strike temp", fmt(water.strikeTempC, 1), "°C", accent = true, valueId = "strike-temp")
            statTile("Sparge water", fmt(water.spargeVolumeL, 1), "L", valueId = "sparge-water")
        }
        fieldGrid(columns = 3) {
            field("Grain temp (°C)") {
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

private fun boilClock(min: Int) = "$min:00"
