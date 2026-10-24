package uk.beertool.auth

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import uk.beertool.common.Env

class SessionCookieTest {

    @Test
    fun `should mark the session cookie secure when the site is served over https`() {
        val cookie = sessionCookie(env("PUBLIC_URL" to "https://beertool.example"))

        cookie shouldContain "Secure"
    }

    @Test
    fun `should leave the session cookie usable over plain http in local development`() {
        val cookie = sessionCookie(env())

        cookie shouldNotContain "Secure"
    }

    private fun env(vararg pairs: Pair<String, String>): Env = mapOf("SESSION_SECRET" to "test-secret", *pairs)::get

    private fun sessionCookie(env: Env): String {
        var header = ""
        testApplication {
            application {
                configureSecurity(env)
                routing { get("/session") { call.sessions.set(UserSession(1)); call.respondText("ok") } }
            }

            header = client.get("/session").headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("BEERTOOL_SESSION=") }
        }
        return header
    }
}
