package uk.beertool.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.auth.PasswordHasher
import uk.beertool.auth.configureSecurity
import uk.beertool.db.TestDatabase
import uk.beertool.user.UserRepository
import java.util.UUID

class AuthFlowTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
    }

    @AfterEach
    fun tearDown() {
        SingleUser.email = null
    }

    private fun newOwner(): String {
        val email = "owner-${UUID.randomUUID()}@beertool.test"
        UserRepository.create(email, PasswordHasher.hash("secret123"), "Owner")
        return email
    }

    private fun authApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureRouting()
        }
        block()
    }

    @Test
    fun `should redirect an anonymous request to login in normal mode`() = authApp {
        val client = createClient { followRedirects = false }

        val response = client.get("/recipes")

        response.status shouldBe HttpStatusCode.Found
        response.headers[HttpHeaders.Location] shouldBe "/login"
    }

    @Test
    fun `should register a user and then serve their pages`() = authApp {
        val client = createClient { install(HttpCookies); followRedirects = false }

        val register = client.post("/register") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=${UUID.randomUUID()}@beertool.test&displayName=A&password=secret123")
        }
        register.status shouldBe HttpStatusCode.Found
        register.headers[HttpHeaders.Location] shouldBe "/recipes"

        client.get("/recipes").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `should reject a login with the wrong password`() = authApp {
        val email = newOwner()
        val client = createClient { followRedirects = false }

        val response = client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=$email&password=wrong-password")
        }

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "Invalid email or password"
    }

    @Test
    fun `should serve recipes read-only to an anonymous visitor in single-user mode`() {
        SingleUser.email = newOwner()
        authApp {
            val client = createClient { followRedirects = false }

            client.get("/recipes").status shouldBe HttpStatusCode.OK
            client.get("/").headers[HttpHeaders.Location] shouldBe "/recipes"
        }
    }

    @Test
    fun `should block writes and registration for an anonymous visitor in single-user mode`() {
        SingleUser.email = newOwner()
        authApp {
            val client = createClient { followRedirects = false }

            val create = client.post("/recipes") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("name=Injected")
            }
            create.headers[HttpHeaders.Location] shouldBe "/login"
            client.get("/recipes/new").headers[HttpHeaders.Location] shouldBe "/login"
            client.get("/register").headers[HttpHeaders.Location] shouldBe "/login"
            client.get("/settings").headers[HttpHeaders.Location] shouldBe "/login"
        }
    }

    @Test
    fun `should grant write access after the owner logs in`() {
        val email = newOwner()
        SingleUser.email = email
        authApp {
            val client = createClient { install(HttpCookies); followRedirects = false }

            val login = client.post("/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("email=$email&password=secret123")
            }
            login.headers[HttpHeaders.Location] shouldBe "/recipes"

            client.get("/recipes/new").status shouldBe HttpStatusCode.OK
        }
    }
}
