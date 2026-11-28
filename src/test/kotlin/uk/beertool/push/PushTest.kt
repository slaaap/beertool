package uk.beertool.push

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.auth.PasswordHasher
import uk.beertool.auth.configureSecurity
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.brewhouse.Phase
import uk.beertool.brewhouse.Session
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.SingleUser
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import uk.beertool.web.configureRouting
import java.time.Instant
import java.util.UUID

class PushTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
    }

    @AfterEach
    fun tearDown() {
        Push.notifier = null
    }

    @Test
    fun `should keep a brewer's phone subscription for alerts`() = pushApp { brewer ->
        val client = signedIn(brewer)

        val saved = client.post(PUSH_PATH) { contentType(ContentType.Application.Json); setBody(subscriptionJson(APPLE)) }

        saved.status shouldBe HttpStatusCode.NoContent
        PushSubscriptionRepository.forUser(brewer.user.id).map { it.endpoint } shouldBe listOf(APPLE)
    }

    @Test
    fun `should refuse a subscription to anywhere but a browser push service`() = pushApp { brewer ->
        val client = signedIn(brewer)

        val saved = client.post(PUSH_PATH) { contentType(ContentType.Application.Json); setBody(subscriptionJson("https://example.com/collect")) }

        saved.status shouldBe HttpStatusCode.BadRequest
        PushSubscriptionRepository.forUser(brewer.user.id).shouldBeEmpty()
    }

    @Test
    fun `should refuse a subscription sent as a form, as another site could forge one`() = pushApp { brewer ->
        val client = signedIn(brewer)

        val saved = client.post(PUSH_PATH) { contentType(ContentType.Application.FormUrlEncoded); setBody("endpoint=$APPLE") }

        saved.status shouldBe HttpStatusCode.UnsupportedMediaType
    }

    @Test
    fun `should refuse a visitor who is not signed in`() = pushApp {
        val saved = client.post(PUSH_PATH) { contentType(ContentType.Application.Json); setBody(subscriptionJson(APPLE)) }

        saved.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should leave notifications off unless switched on, even with keys set`() {
        val env = mapOf("VAPID_PUBLIC_KEY" to KEYS.publicKeyText, "VAPID_PRIVATE_KEY" to "x", "PUBLIC_URL" to "https://beertool.uk")

        val config = PushConfig.fromEnv(env::get)

        config.shouldBeNull()
    }

    @Test
    fun `should send the brewer's phone an encrypted, signed alert when the strike water is ready`() {
        val brewer = newBrewer()
        PushSubscriptionRepository.save(brewer.user.id, subscription(APPLE))
        val delivered = CompletableDeferred<Pair<String, Map<String, String>>>()
        val notifier = PushNotifier(CONFIG, { endpoint, headers, _ -> delivered.complete(endpoint to headers); 201 })

        notifier.brewhouseReport(brewer.user, session(brewer, Phase.STRIKE), session(brewer, Phase.DOUGH_IN))

        val (endpoint, headers) = runBlocking { withTimeout(5000) { delivered.await() } }
        endpoint shouldBe APPLE
        headers["Authorization"]!! shouldStartWith "vapid t="
        headers["Content-Encoding"] shouldBe "aes128gcm"
    }

    @Test
    fun `should forget a subscription the push service says is gone`() {
        val brewer = newBrewer()
        PushSubscriptionRepository.save(brewer.user.id, subscription(APPLE))
        val notifier = PushNotifier(CONFIG, { _, _, _ -> 410 })

        runBlocking { notifier.send(subscription(APPLE), "{}".toByteArray()) }

        PushSubscriptionRepository.forUser(brewer.user.id).shouldBeEmpty()
    }

    private data class Brewer(val email: String, val user: User, val batchId: Long)

    private fun session(brewer: Brewer, phase: Phase) = Session(batchId = brewer.batchId, phase = phase, updatedAt = Instant.parse("2026-11-28T12:00:00Z"))

    private suspend fun ApplicationTestBuilder.signedIn(brewer: Brewer): HttpClient {
        val client = createClient { install(HttpCookies); followRedirects = false }
        client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=${brewer.email}&password=secret123")
        }
        return client
    }

    private fun newBrewer(): Brewer {
        val email = "push-${UUID.randomUUID()}@beertool.test"
        val user = UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        val recipe = RecipeRepository.create(user.id, NewRecipe(name = "Pale"))
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id))!!
        return Brewer(email, user, batch.id)
    }

    private fun pushApp(block: suspend ApplicationTestBuilder.(Brewer) -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureRouting()
            configurePush(CONFIG, gateway = { _, _, _ -> 201 })
        }
        block(newBrewer())
    }

    private companion object {
        const val APPLE = "https://web.push.apple.com/QGuQyavXutnMH4Q"
        val SUBSCRIBER = P256.newKeyPair()
        val AUTH: ByteArray = P256.randomBytes(16)
        val KEYS = P256.newKeyPair().let { pair ->
            val d = (pair.private as java.security.interfaces.ECPrivateKey).s.toByteArray().let { b ->
                if (b.size == 32) b else if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
            }
            VapidKeys(P256.raw(pair.public), d)
        }
        val CONFIG = PushConfig(KEYS, "https://beertool.uk")

        fun subscription(endpoint: String) = PushSubscription(endpoint, b64url(P256.raw(SUBSCRIBER.public)), b64url(AUTH))

        fun subscriptionJson(endpoint: String) =
            """{"endpoint":"$endpoint","keys":{"p256dh":"${b64url(P256.raw(SUBSCRIBER.public))}","auth":"${b64url(AUTH)}"}}"""
    }
}
