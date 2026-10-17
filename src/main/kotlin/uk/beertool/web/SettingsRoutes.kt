package uk.beertool.web

import io.ktor.server.html.respondHtml
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import kotlinx.html.*
import kotlinx.serialization.Serializable
import uk.beertool.recipe.MashStep
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import uk.beertool.mcp.ApiTokenRepository
import uk.beertool.mcp.MCP_PATH
import uk.beertool.mcp.Mcp
import uk.beertool.telegram.Telegram
import java.time.ZoneId
import uk.beertool.telegram.TelegramLinkRepository
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
    @Serializable(with = BlankAsNullDouble::class) val whirlpoolTempC: Double? = null,
    @Serializable(with = BlankAsNullInt::class) val coolingTimeMin: Int? = null,
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
    whirlpoolTempC = whirlpoolTempC ?: current.whirlpoolTempC,
    coolingTimeMin = coolingTimeMin ?: current.coolingTimeMin,
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

    get<SettingsConnections> {
        withUser(write = true) { call, user ->
            if (!hasConnections()) return@withUser call.respondRedirect("/settings")
            call.respondHtml { connectionsPage(user, call.mcpEndpoint()) }
        }
    }

    post<Settings> {
        withUser(write = true) { call, user ->
            val updated = call.receiveForm<SettingsForm>().toPreferences(user.preferences)
            UserRepository.updatePreferences(user.id, updated)
            call.respondRedirect("/settings?saved=true")
        }
    }

    post<SettingsTokens> {
        withUser(write = true) { call, user ->
            if (!Mcp.enabled) return@withUser call.respondRedirect(CONNECTIONS)
            val name = call.receiveForm<TokenForm>().name.trim().ifBlank { "Access token" }.take(80)
            val token = ApiTokenRepository.create(user.id, name)
            call.respondHtml { connectionsPage(user, call.mcpEndpoint(), NewToken(name, token)) }
        }
    }

    post<SettingsTokenRevoke> { res ->
        withUser(write = true) { call, user ->
            ApiTokenRepository.revoke(res.id, user.id)
            call.respondRedirect(CONNECTIONS)
        }
    }

    post<SettingsTelegram> {
        withUser(write = true) { call, user ->
            val bot = Telegram.botUsername
            if (bot == null) call.respondRedirect(CONNECTIONS)
            else call.respondRedirect("https://t.me/$bot?start=${TelegramLinkRepository.issueToken(user.id)}")
        }
    }

    post<SettingsTelegramUnlink> {
        withUser(write = true) { call, user ->
            TelegramLinkRepository.unlinkUser(user.id)
            call.respondRedirect(CONNECTIONS)
        }
    }
}

data class NewToken(val name: String, val token: String)

@Serializable
data class TokenForm(val name: String = "")

private const val CONNECTIONS = "/settings/connections"

private fun hasConnections() = Telegram.enabled || Mcp.enabled

private fun FlowContent.settingsHead(current: String) {
    h1 { +"Settings" }
    if (!hasConnections()) return
    nav("subnav") {
        listOf("/settings" to "Brewing", CONNECTIONS to "Connections").forEach { (href, label) ->
            a(href = href, classes = if (href == current) "current" else null) { +label }
        }
    }
}

fun HTML.connectionsPage(user: User, mcpEndpoint: String?, newToken: NewToken? = null) {
    page("Connections", user) {
        settingsHead(CONNECTIONS)
        if (Telegram.enabled) telegramSection(user)
        mcpEndpoint?.let { assistantSection(user, it, newToken) }
    }
}

fun HTML.settingsPage(user: User, saved: Boolean) {
    val prefs = user.preferences
    page("Settings", user) {
        settingsHead("/settings")

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

            sectionHead("Whirlpool")
            fieldGrid(columns = 2) {
                field("Hops in at (°C)") {
                    numberInput(name = "whirlpoolTempC") { value = fmt(prefs.whirlpoolTempC, 0); numAttrs("1", max = "100") }
                }
                field("Chill to pitching (min)") {
                    numberInput(name = "coolingTimeMin") { value = prefs.coolingTimeMin.toString(); numAttrs("1") }
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

private fun FlowContent.telegramSection(user: User) {
    sectionHead("Telegram")
    if (TelegramLinkRepository.findByUser(user.id) == null) {
        actionBar { btnPost("/settings/telegram", "Connect Telegram", Icon.PLUS) }
    } else {
        p("muted") { +"Connected to @${Telegram.botUsername}." }
        actionBar { btnPost("/settings/telegram/unlink", "Disconnect", Icon.TRASH, style = ButtonStyle.DANGER) }
    }
}

private fun ApplicationCall.mcpEndpoint(): String? {
    if (!Mcp.enabled) return null
    val base = Mcp.publicUrl ?: request.origin.let { o ->
        val port = if (o.serverPort == 80 || o.serverPort == 443) "" else ":${o.serverPort}"
        "${o.scheme}://${o.serverHost}$port"
    }
    return base + MCP_PATH
}

private fun FlowContent.assistantSection(user: User, endpoint: String, newToken: NewToken?) {
    sectionHead("AI assistant")
    p("muted") { +"MCP endpoint "; code { +endpoint } }
    newToken?.let {
        div("token-box") {
            p { strong { +"Copy the token for “${it.name}” now." }; +" It won't be shown again." }
            code("token") { +it.token }
            p("muted") { +"To connect Claude Code:" }
            code("token") { +"claude mcp add --transport http beertool $endpoint --header \"Authorization: Bearer ${it.token}\"" }
        }
    }
    val tokens = ApiTokenRepository.listByUser(user.id)
    if (tokens.isNotEmpty()) {
        table {
            thead { tr { th { +"Token" }; th { +"Created" }; th { +"Last used" }; th {} } }
            tbody {
                tokens.forEach { t ->
                    tr {
                        td { +t.name }
                        td { +fmt(t.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()) }
                        td { +(t.lastUsedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()?.toString() ?: "never") }
                        td("right") { deleteButton("/settings/tokens/${t.id}/revoke", "Revoke “${t.name}”? Anything using it will lose access.") }
                    }
                }
            }
        }
    }
    form(action = "/settings/tokens", method = FormMethod.post) {
        fieldGrid(columns = 2) {
            field("New token name") { textInput(name = "name") { placeholder = "e.g. Claude Code on my laptop" } }
        }
        actionBar { submitInput(classes = "btn") { value = "Create token" } }
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
