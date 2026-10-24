package uk.beertool

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.resources.Resources
import uk.beertool.auth.configureSecurity
import uk.beertool.common.systemEnv
import uk.beertool.common.text
import uk.beertool.db.DatabaseFactory
import uk.beertool.mcp.configureMcp
import uk.beertool.telegram.configureTelegram
import uk.beertool.web.configureErrorPages
import uk.beertool.web.configureRouting

fun main() {
    val port = systemEnv.text("PORT")?.toIntOrNull() ?: 8080
    val host = systemEnv.text("HOST") ?: "127.0.0.1"
    embeddedServer(Netty, port = port, host = host, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    DatabaseFactory.init()
    install(CallLogging)
    install(Resources)
    configureSecurity()
    configureErrorPages()
    configureRouting()
    configureMcp()
    configureTelegram()
}
