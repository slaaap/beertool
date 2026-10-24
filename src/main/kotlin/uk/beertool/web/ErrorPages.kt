package uk.beertool.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.defaultExceptionStatusCode
import io.ktor.server.engine.handleFailure
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import kotlinx.html.*
import org.slf4j.LoggerFactory
import uk.beertool.common.io
import uk.beertool.mcp.MCP_PATH

private val log = LoggerFactory.getLogger("uk.beertool.web.ErrorPages")

fun Application.configureErrorPages() {
    install(StatusPages) {
        exception<InvalidInput> { call, cause ->
            call.respondErrorPage(HttpStatusCode.BadRequest, "That didn't add up", cause.message)
        }
        exception<Throwable> { call, cause ->
            val status = defaultExceptionStatusCode(cause) ?: HttpStatusCode.InternalServerError
            when {
                call.isApi() -> handleFailure(call, cause)
                status == HttpStatusCode.InternalServerError -> {
                    log.error("Unhandled error on ${call.request.path()}", cause)
                    call.respondErrorPage(status, "Something went wrong", "The error has been logged. Try again, or go back and check what you entered.")
                }
                else -> call.respondErrorPage(status, "That didn't work", "The page couldn't make sense of that request.")
            }
        }
    }
}

// MCP and OAuth clients read JSON or bare status codes, so they keep Ktor's own error handling.
private fun ApplicationCall.isApi() = request.path().let {
    it == MCP_PATH || it.startsWith("$MCP_PATH/") || it.startsWith("/oauth/") || it.startsWith("/.well-known/")
}

private suspend fun ApplicationCall.respondErrorPage(status: HttpStatusCode, heading: String, message: String?) {
    // The failure may be the database itself, so the page must not depend on it.
    val user = runCatching { io { currentUser() } }.getOrNull()
    respondHtml(status) {
        page(heading, user) {
            h1 { +heading }
            message?.let { p("alert") { +it } }
            actionBar {
                button(type = ButtonType.button, classes = "btn") {
                    onClick = "history.back()"
                    iconSvg(Icon.BACK)
                    span { +"Go back" }
                }
            }
        }
    }
}
