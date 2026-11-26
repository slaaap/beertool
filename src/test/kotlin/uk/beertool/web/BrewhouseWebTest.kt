package uk.beertool.web

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.brewhouse.BREWHOUSE_PATH
import uk.beertool.brewhouse.BREWHOUSE_WEB_PATH
import uk.beertool.brewhouse.Brewhouse
import uk.beertool.brewhouse.BrewhouseConfig
import uk.beertool.brewhouse.BrewhouseKeyRepository
import uk.beertool.brewhouse.configureBrewhouse
import uk.beertool.db.TestDatabase
import uk.beertool.mcp.Mcp
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeRepository
import uk.beertool.telegram.Telegram
import uk.beertool.user.SingleUser
import uk.beertool.user.UserRepository
import java.util.UUID

class BrewhouseWebTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
        Mcp.enabled = false
        Mcp.publicUrl = null
        Mcp.tokensEnabled = false
        Telegram.botUsername = null
    }

    @AfterEach
    fun tearDown() {
        SingleUser.email = null
        Brewhouse.service = null
    }

    @Test
    fun `should show the signed-in brewer the brewhouse status`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)

        val response = client.get("$BREWHOUSE_WEB_PATH/status")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "\"online\":false"
        response.bodyAsText() shouldContain "\"serverNow\":"
    }

    @Test
    fun `should turn away a visitor who is not signed in`() = brewhouseWebApp {
        val response = client.get("$BREWHOUSE_WEB_PATH/status")

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should show showcase visitors nothing of the brewhouse`() = brewhouseWebApp { brewer ->
        SingleUser.email = brewer.email

        val status = client.get("$BREWHOUSE_WEB_PATH/status")
        val page = client.get(brewer.brewDayUrl).bodyAsText()

        status.status shouldBe HttpStatusCode.Unauthorized
        page shouldNotContain "id=\"bh\""
        page shouldNotContain "brewhouse.js"
    }

    @Test
    fun `should queue a command from the page for the brewhouse to collect`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)
        client.post("$BREWHOUSE_PATH/sync") {
            header(HttpHeaders.Authorization, "Bearer ${brewer.token}")
            contentType(ContentType.Application.Json)
            setBody(IDLE_REPORT)
        }

        val queued = client.post("$BREWHOUSE_WEB_PATH/commands") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"start","batchId":${brewer.batchId}}""")
        }
        val collected = client.post("$BREWHOUSE_PATH/sync") {
            header(HttpHeaders.Authorization, "Bearer ${brewer.token}")
            contentType(ContentType.Application.Json)
            setBody(IDLE_REPORT)
        }.bodyAsText()

        queued.status shouldBe HttpStatusCode.Accepted
        collected shouldContain "\"type\":\"start\""
        collected shouldContain "\"batchId\":${brewer.batchId}"
    }

    @Test
    fun `should start the brewhouse with the strike water the brewer adjusted on the page`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)
        client.post("$BREWHOUSE_PATH/sync") {
            header(HttpHeaders.Authorization, "Bearer ${brewer.token}")
            contentType(ContentType.Application.Json)
            setBody(IDLE_REPORT)
        }

        client.post("$BREWHOUSE_WEB_PATH/commands") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"start","batchId":${brewer.batchId},"grainTempC":20,"mashThicknessLPerKg":2.0}""")
        }
        val collected = client.post("$BREWHOUSE_PATH/sync") {
            header(HttpHeaders.Authorization, "Bearer ${brewer.token}")
            contentType(ContentType.Application.Json)
            setBody(IDLE_REPORT)
        }.bodyAsText()

        collected shouldContain "\"waterL\":9.0"
    }

    @Test
    fun `should refuse a start with a grain temperature no brewer could mean`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)

        val response = client.post("$BREWHOUSE_WEB_PATH/commands") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"start","batchId":${brewer.batchId},"grainTempC":95}""")
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `should refuse a command posted as a form, as another site could forge one`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)

        val response = client.post("$BREWHOUSE_WEB_PATH/commands") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("type=stop")
        }

        response.status shouldBe HttpStatusCode.UnsupportedMediaType
    }

    @Test
    fun `should not let a brewer read another brewer's readings`() = brewhouseWebApp { brewer ->
        val other = newBrewer()
        val client = signedIn(brewer)

        val response = client.get("$BREWHOUSE_WEB_PATH/batches/${other.batchId}/readings")

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `should put the brewhouse panel and its start preview on the brewer's Brew Day page`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)

        val page = client.get(brewer.brewDayUrl).bodyAsText()

        page shouldContain "id=\"bh\""
        page shouldContain "Start on brewhouse"
        page shouldContain "brewhouse.js"
    }

    @Test
    fun `should leave the panel off the Brew Day page while the brewhouse is switched off`() = brewhouseWebApp(enabled = false) { brewer ->
        val client = signedIn(brewer)

        val page = client.get(brewer.brewDayUrl).bodyAsText()

        page shouldNotContain "id=\"bh\""
    }

    @Test
    fun `should show on Connections that the brewhouse has not connected yet, with the URL to give it`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)

        val page = client.get("/settings/connections").bodyAsText()

        page shouldContain "Brewhouse"
        page shouldContain "Not connected yet"
        page shouldContain "$BREWHOUSE_PATH</code>"
        page shouldContain "Make a new key"
        page shouldNotContain "Personal tokens"
    }

    @Test
    fun `should show on Connections that the brewhouse is online once it syncs`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)
        client.post("$BREWHOUSE_PATH/sync") {
            header(HttpHeaders.Authorization, "Bearer ${brewer.token}")
            contentType(ContentType.Application.Json)
            setBody(IDLE_REPORT)
        }

        val page = client.get("/settings/connections").bodyAsText()

        page shouldContain "Online · beertool-brewhouse 1.0"
    }

    @Test
    fun `should show a new brewhouse key once, and only that key opens the API`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)

        val created = client.post("/settings/brewhouse/key").bodyAsText()
        val later = client.get("/settings/connections").bodyAsText()

        val key = Regex("bh_[A-Za-z0-9_-]+").find(created).shouldNotBeNull().value
        client.get("$BREWHOUSE_PATH/status") { header(HttpHeaders.Authorization, "Bearer $key") }.status shouldBe HttpStatusCode.OK
        client.get("$BREWHOUSE_PATH/status") { header(HttpHeaders.Authorization, "Bearer ${brewer.token}") }.status shouldBe HttpStatusCode.Unauthorized
        later shouldContain "Brewhouse key"
        later shouldNotContain key
    }

    @Test
    fun `should disconnect the brewhouse from Connections`() = brewhouseWebApp { brewer ->
        val client = signedIn(brewer)

        client.post("/settings/brewhouse/key/revoke")
        val page = client.get("/settings/connections").bodyAsText()

        client.get("$BREWHOUSE_PATH/status") { header(HttpHeaders.Authorization, "Bearer ${brewer.token}") }.status shouldBe HttpStatusCode.Unauthorized
        page shouldContain "Connect a brewhouse"
        page shouldNotContain "Brewhouse key"
    }

    @Test
    fun `should leave the brewhouse off Connections while it is switched off`() = brewhouseWebApp(enabled = false) { brewer ->
        val client = signedIn(brewer)

        val response = client.get("/settings/connections")

        response.headers[HttpHeaders.Location] shouldBe "/settings"
    }

    private data class Brewer(val email: String, val token: String, val batchId: Long, val brewDayUrl: String)

    private suspend fun ApplicationTestBuilder.signedIn(brewer: Brewer): HttpClient {
        val client = createClient { install(HttpCookies); followRedirects = false }
        client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=${brewer.email}&password=secret123")
        }
        return client
    }

    private fun newBrewer(): Brewer {
        val email = "bh-web-${UUID.randomUUID()}@beertool.test"
        val user = UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        val recipe = RecipeRepository.create(user.id, NewRecipe(name = "Pale", fermentables = listOf(RecipeFermentable("Maris Otter", 4.5, FermentableType.MALT))))
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id))!!
        return Brewer(email, BrewhouseKeyRepository.issue(user.id), batch.id, "/recipes/${recipe.no}/batches/${batch.no}/brew")
    }

    private fun brewhouseWebApp(enabled: Boolean = true, block: suspend ApplicationTestBuilder.(Brewer) -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureRouting()
            configureBrewhouse(if (enabled) BrewhouseConfig() else null)
        }
        block(newBrewer())
    }

    private companion object {
        val IDLE_REPORT = """
            {"software":"beertool-brewhouse 1.0","ready":true,"session":null,"commandResults":[],
             "devices":{"hob":{"online":true},"thermometer":{"online":true},"pump":{"online":true}}}
        """.trimIndent()
    }
}
