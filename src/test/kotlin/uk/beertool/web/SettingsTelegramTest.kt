package uk.beertool.web

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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
import uk.beertool.telegram.Telegram
import uk.beertool.telegram.TelegramLinkRepository
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.util.UUID

class SettingsTelegramTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
        Telegram.botUsername = null
    }

    @AfterEach
    fun tearDown() {
        Telegram.botUsername = null
    }

    @Test
    fun `should not mention Telegram when the bot is off`() = settingsApp { client, _ ->
        val page = client.get("/settings").bodyAsText()

        page shouldNotContain "Connect Telegram"
    }

    @Test
    fun `should send the brewer to the bot with a one-time link token`() = settingsApp { client, user ->
        Telegram.botUsername = "beertool_test_bot"

        val response = client.post("/settings/telegram")

        val location = response.headers[HttpHeaders.Location]!!
        location shouldStartWith "https://t.me/beertool_test_bot?start="
        val link = TelegramLinkRepository.redeem(location.substringAfter("start="), telegramUserId = 7, chatId = 7)
        link!!.userId shouldBe user.id
    }

    @Test
    fun `should show a linked account and let the brewer disconnect it`() = settingsApp { client, user ->
        Telegram.botUsername = "beertool_test_bot"
        TelegramLinkRepository.redeem(TelegramLinkRepository.issueToken(user.id), telegramUserId = 8, chatId = 8)

        val page = client.get("/settings").bodyAsText()
        client.post("/settings/telegram/unlink")

        page shouldContain "Connected to @beertool_test_bot"
        TelegramLinkRepository.findByUser(user.id).shouldBeNull()
    }

    private fun settingsApp(block: suspend ApplicationTestBuilder.(HttpClient, User) -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureRouting()
        }
        val email = "tg-${UUID.randomUUID()}@beertool.test"
        val user = UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        val client = createClient { install(HttpCookies); followRedirects = false }
        client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=$email&password=secret123")
        }
        block(client, user)
    }
}
