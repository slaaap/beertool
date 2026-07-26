package uk.beertool.web

import io.ktor.server.application.Application
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.resources.get
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.routing
import kotlinx.html.*

fun Application.configureRouting() {
    routing {
        staticResources("/static", "static")

        authRoutes()

        get<Home> {
            if (call.currentUser() != null) call.respondRedirect("/recipes")
            else call.respondHtml { page("Home") { welcomeBody() } }
        }

        recipeRoutes()
        batchRoutes()
        settingsRoutes()
    }
}

private fun FlowContent.welcomeBody() {
    div("hero") {
        brandMark()
        h1 { +"Brew it. Log it. Brew it better." }
        p("lede") { +"Write beer recipes, see the numbers as you go, and keep a log of every brew you pull off." }
        actionBar {
            if (!SingleUser.enabled) btnLink("/register", "Create an account", Icon.PLUS, style = ButtonStyle.PRIMARY)
            btnLink("/login", "Log in", Icon.BACK)
        }
    }
}
