package uk.beertool.web

import io.ktor.server.html.respondHtml
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import kotlinx.html.*
import kotlinx.serialization.Serializable
import uk.beertool.recipe.MashStep
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.User
import uk.beertool.user.UserRepository

@Serializable
data class SettingsForm(
    @Serializable(with = BlankAsNullDouble::class) val preBoilVolumeL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val boilOffL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val kettleRetentionL: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val efficiency: Double? = null,
    @Serializable(with = BlankAsNullInt::class) val boilTimeMin: Int? = null,
    @Serializable(with = BlankAsNullDouble::class) val mashThicknessLPerKg: Double? = null,
    @Serializable(with = BlankAsNullDouble::class) val grainAbsorptionLPerKg: Double? = null,
    val mashSteps: List<MashStepRow> = emptyList(),
)

fun SettingsForm.toPreferences(current: BrewerPreferences) = current.copy(
    preBoilVolumeL = preBoilVolumeL ?: current.preBoilVolumeL,
    boilOffL = boilOffL ?: current.boilOffL,
    kettleRetentionL = kettleRetentionL ?: current.kettleRetentionL,
    efficiency = efficiency?.let(::percentToFraction) ?: current.efficiency,
    boilTimeMin = boilTimeMin ?: current.boilTimeMin,
    mashThicknessLPerKg = mashThicknessLPerKg ?: current.mashThicknessLPerKg,
    grainAbsorptionLPerKg = grainAbsorptionLPerKg ?: current.grainAbsorptionLPerKg,
    mashSteps = mashSteps.mapNotNull { row ->
        MashStep(tempC = row.tempC ?: return@mapNotNull null, timeMin = row.timeMin ?: return@mapNotNull null)
    },
)

fun Route.settingsRoutes() {
    get<Settings> { res ->
        withUser(write = true) { call, user ->
            call.respondHtml { settingsPage(user, saved = res.saved) }
        }
    }

    post<Settings> {
        withUser(write = true) { call, user ->
            val updated = call.receiveForm<SettingsForm>().toPreferences(user.preferences)
            UserRepository.updatePreferences(user.id, updated)
            call.respondRedirect("/settings?saved=true")
        }
    }
}

fun HTML.settingsPage(user: User, saved: Boolean) {
    val prefs = user.preferences
    page("Settings", user) {
        h1 { +"Brewing preferences" }

        form(action = "/settings", method = FormMethod.post) {
            id = "settings-form"
            sectionHead("Your kit")
            fieldGrid(columns = 3) {
                field("Pre-boil (L)") {
                    numberInput(name = "preBoilVolumeL") {
                        value = prefs.preBoilVolumeL.toString(); numAttrs("0.5")
                    }
                }
                field("Boil-off (L)") {
                    numberInput(name = "boilOffL") { value = prefs.boilOffL.toString(); numAttrs("0.5") }
                }
                field("Kettle loss (L)") {
                    numberInput(name = "kettleRetentionL") {
                        value = prefs.kettleRetentionL.toString(); numAttrs("0.5")
                    }
                }
            }

            div("stats derived") {
                statTile("Post-boil", fmt(prefs.postBoilVolumeL, 1), "L", valueId = "d-post")
                statTile("Fermenter", fmt(prefs.fermenterVolumeL, 1), "L", valueId = "d-ferm")
            }

            sectionHead("Your brew day")
            fieldGrid(columns = 2) {
                field("Efficiency (%)") {
                    numberInput(name = "efficiency") {
                        value = fmt(prefs.efficiency.asPercent(), 0); numAttrs("1", max = "100")
                    }
                }
                field("Boil (min)") {
                    numberInput(name = "boilTimeMin") { value = prefs.boilTimeMin.toString(); numAttrs("1") }
                }
            }

            sectionHead("Mash water")
            fieldGrid(columns = 2) {
                field("Mash thickness (L/kg)") {
                    numberInput(name = "mashThicknessLPerKg") {
                        value = fmt(prefs.mashThicknessLPerKg, 1); numAttrs("0.1")
                    }
                }
                field("Grain absorption (L/kg)") {
                    numberInput(name = "grainAbsorptionLPerKg") {
                        value = fmt(prefs.grainAbsorptionLPerKg, 1); numAttrs("0.1")
                    }
                }
            }

            sectionHead("Default mash") {
                button(type = ButtonType.button, classes = "btn small") {
                    onClick = "smAdd()"
                    span("ico") { unsafe { +Icon.PLUS.markup() } }
                    span { +"Add rest" }
                }
            }
            table("mash") {
                thead { tr { head("Rest", "c-name"); numHead("Temp", "°C"); numHead("Time", "min"); actionSlot() } }
                tbody { id = "sm-body"; prefs.mashSteps.forEachIndexed { i, m -> mashPrefRow(i, m) } }
            }

            actionBar {
                submitInput(classes = "btn primary") { value = "Save preferences" }
                btnLink("/recipes", "Cancel", Icon.BACK)
                if (saved) span("saved-note") { id = "saved-note"; +"Saved ✓" }
            }
        }

        table { style = "display:none"; tbody { id = "sm-proto"; mashPrefRow(0, null) } }

        js("settings.js")
        js("ui.js")
    }
}

private fun TBODY.mashPrefRow(i: Int, step: MashStep?) {
    tr {
        td("c-name") {}
        td("num") { numberInput(name = "mashSteps.$i.tempC") { value = step?.tempC?.toString() ?: ""; numAttrs("0.5") } }
        td("num") { numberInput(name = "mashSteps.$i.timeMin") { value = step?.timeMin?.toString() ?: ""; numAttrs("1") } }
        td("right") { rowRemoveButton("smRemove(this)", "Remove rest") }
    }
}
