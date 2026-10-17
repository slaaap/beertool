package uk.beertool.web

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import uk.beertool.mcp.ApiTokenRepository
import uk.beertool.mcp.Mcp
import uk.beertool.telegram.Telegram
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.util.UUID

class SettingsTokensTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
        Mcp.enabled = true
        Mcp.publicUrl = "https://beer.example"
        Telegram.botUsername = null
    }

    @AfterEach
    fun tearDown() {
        Mcp.enabled = false
        Mcp.publicUrl = null
    }

    @Test
    fun `should not offer access tokens when the MCP endpoint is off`() = settingsApp { client, _ ->
        Mcp.enabled = false

        val page = client.get("/settings").bodyAsText()

        page shouldNotContain "AI assistant"
    }

    @Test
    fun `should show a new token once, with the command to connect Claude Code`() = settingsApp { client, user ->
        val created = client.post("/settings/tokens") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Laptop")
        }.bodyAsText()
        val later = client.get("/settings/connections").bodyAsText()

        val token = Regex("bt_[A-Za-z0-9_-]+").find(created).shouldNotBeNull().value
        ApiTokenRepository.userFor(token)!!.id shouldBe user.id
        created shouldContain "claude mcp add --transport http beertool https://beer.example/mcp"
        later shouldContain "Laptop"
        later shouldNotContain token
    }

    @Test
    fun `should revoke a token from settings`() = settingsApp { client, user ->
        ApiTokenRepository.create(user.id, "Old laptop")
        val id = ApiTokenRepository.listByUser(user.id).single().id

        client.post("/settings/tokens/$id/revoke")

        ApiTokenRepository.listByUser(user.id).shouldBeEmpty()
    }

    @Test
    fun `should keep brewing preferences and connections on separate tabs`() = settingsApp { client, _ ->
        val brewing = client.get("/settings").bodyAsText()
        val connections = client.get("/settings/connections").bodyAsText()

        brewing shouldContain "Your kit"
        brewing shouldContain "href=\"/settings/connections\""
        brewing shouldNotContain "AI assistant"
        connections shouldContain "AI assistant"
        connections shouldNotContain "Your kit"
    }

    @Test
    fun `should send connections back to brewing settings when there is nothing to connect`() = settingsApp { client, _ ->
        Mcp.enabled = false

        val response = client.get("/settings/connections")
        val brewing = client.get("/settings").bodyAsText()

        response.headers[HttpHeaders.Location] shouldBe "/settings"
        brewing shouldNotContain "Connections"
    }

    @Test
    fun `should never list another brewer's tokens`() = settingsApp { client, _ ->
        val other = UserRepository.create("other-${UUID.randomUUID()}@beertool.test", "hash", "Other")
        ApiTokenRepository.create(other.id, "Other brewer's laptop")

        val page = client.get("/settings/connections").bodyAsText()

        page shouldNotContain "Other brewer"
    }

    private fun settingsApp(block: suspend ApplicationTestBuilder.(HttpClient, User) -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureRouting()
        }
        val email = "tokens-${UUID.randomUUID()}@beertool.test"
        val user = UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        val client = createClient { install(HttpCookies); followRedirects = false }
        client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=$email&password=secret123")
        }
        block(client, user)
    }
}
