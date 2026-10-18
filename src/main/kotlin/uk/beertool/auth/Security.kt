package uk.beertool.auth

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.sessions.SessionSerializer
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import org.slf4j.LoggerFactory
import uk.beertool.common.systemEnv
import uk.beertool.common.text
import java.security.SecureRandom

private val log = LoggerFactory.getLogger("uk.beertool.auth.Security")

fun Application.configureSecurity() {
    val secret = systemEnv.text("SESSION_SECRET")?.toByteArray() ?: run {
        log.warn("SESSION_SECRET not set - generating a random key for this run; sessions won't survive a restart. Set it in production.")
        SecureRandom().generateSeed(32)
    }

    install(FramingForbidden)
    install(Sessions) {
        cookie<UserSession>("BEERTOOL_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 7 * 24 * 60 * 60
            cookie.extensions["SameSite"] = "Lax"
            serializer = UserSessionSerializer
            transform(SessionTransportTransformerMessageAuthentication(secret))
        }
    }
}

// No page is meant to be shown inside another site's frame, which keeps the OAuth consent page safe from clickjacking.
private val FramingForbidden = createApplicationPlugin("FramingForbidden") {
    onCall { call ->
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Content-Security-Policy", "frame-ancestors 'none'")
    }
}

private object UserSessionSerializer : SessionSerializer<UserSession> {
    override fun serialize(session: UserSession) = session.userId.toString()
    override fun deserialize(text: String) = UserSession(text.toLong())
}
