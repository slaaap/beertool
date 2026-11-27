package uk.beertool.brewhouse

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BrewhouseContractTest {

    private var clock = Instant.parse("2026-11-25T18:00:00Z")

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @AfterEach
    fun tearDown() {
        Brewhouse.service = null
    }

    @Test
    fun `should write a report the way the spec describes it`() {
        val sent = report(session = session(batchId = 7)).copy(notReady = null, widget = buildJsonObject { put("hob", "2100") })

        val problems = BrewhouseSpec.componentProblems("Report", encode(sent, Report.serializer()))

        problems.shouldBeEmpty()
    }

    @Test
    fun `should catch a body that drifts from the spec, however deep the difference`() {
        val status = """{"online":true,"lastSeenAt":null,"report":{"software":"x","ready":true,"session":{"phase":"brewing","updatedAt":"2026-11-25T18:00:00Z"},"commandResults":[]}}"""

        val problems = BrewhouseSpec.bodyProblems("GET", "/status", 200, status)

        problems.orEmpty().shouldNotBeEmpty()
    }

    @Test
    fun `should catch a status code the spec does not list`() {
        val documented = BrewhouseSpec.documents("GET", "/status", 418)

        documented shouldBe false
    }

    @Test
    fun `should answer a sync as the spec describes`() = contractApp { brewer ->
        val response = client.post("$BREWHOUSE_PATH/sync") { bearer(brewer.key); json(encode(report(session = session(brewer.batchId)), Report.serializer())) }

        response shouldMatch ("POST" to "/sync")
    }

    @Test
    fun `should describe a brewhouse that never synced as the spec does`() = contractApp { brewer ->
        val response = client.get("$BREWHOUSE_PATH/status") { bearer(brewer.key) }

        response shouldMatch ("GET" to "/status")
    }

    @Test
    fun `should describe a brewhouse mid-brew as the spec does`() = contractApp { brewer ->
        client.sync(brewer, report(session = session(brewer.batchId)))

        val response = client.get("$BREWHOUSE_PATH/status") { bearer(brewer.key) }

        response shouldMatch ("GET" to "/status")
    }

    @Test
    fun `should queue a start and report on it as the spec describes`() = contractApp { brewer ->
        client.sync(brewer, report())

        val queued = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.key); json("""{"type":"start","batchId":${brewer.batchId}}""") }
        val id = decode(queued.bodyAsText(), Command.serializer()).id
        val fetched = client.get("$BREWHOUSE_PATH/commands/$id") { bearer(brewer.key) }

        queued shouldMatch ("POST" to "/commands")
        fetched shouldMatch ("GET" to "/commands/{id}")
    }

    @Test
    fun `should refuse a command to an offline brewhouse as the spec describes`() = contractApp { brewer ->
        val response = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.key); json("""{"type":"pause"}""") }

        response shouldMatch ("POST" to "/commands")
    }

    @Test
    fun `should give a batch's schedule as the spec describes`() = contractApp { brewer ->
        val response = client.get("$BREWHOUSE_PATH/batches/${brewer.batchId}/schedule") { bearer(brewer.key) }

        response shouldMatch ("GET" to "/batches/{batchId}/schedule")
    }

    @Test
    fun `should explain a batch that cannot be scheduled as the spec describes`() = contractApp(steps = emptyList()) { brewer ->
        val response = client.get("$BREWHOUSE_PATH/batches/${brewer.batchId}/schedule") { bearer(brewer.key) }

        response shouldMatch ("GET" to "/batches/{batchId}/schedule")
    }

    @Test
    fun `should give a batch's readings as the spec describes`() = contractApp { brewer ->
        client.sync(brewer, report(session = session(brewer.batchId)))
        clock = clock.plus(Duration.ofSeconds(30))
        client.sync(brewer, report(session = session(brewer.batchId)))

        val response = client.get("$BREWHOUSE_PATH/batches/${brewer.batchId}/readings") { bearer(brewer.key) }

        response shouldMatch ("GET" to "/batches/{batchId}/readings")
    }

    @Test
    fun `should take and drop a widget as the spec describes`() = contractApp { brewer ->
        val stored = client.put("$BREWHOUSE_PATH/widget") { bearer(brewer.key); contentType(ContentType.Text.Html); setBody("<p>rig</p>") }
        val dropped = client.delete("$BREWHOUSE_PATH/widget") { bearer(brewer.key) }

        stored shouldMatch ("PUT" to "/widget")
        dropped shouldMatch ("DELETE" to "/widget")
    }

    @Test
    fun `should turn away a missing key as the spec describes`() = contractApp {
        val response = client.get("$BREWHOUSE_PATH/status")

        response shouldMatch ("GET" to "/status")
    }

    @Test
    fun `should reject a malformed report as the spec describes`() = contractApp { brewer ->
        val response = client.post("$BREWHOUSE_PATH/sync") { bearer(brewer.key); json("""{"software":"x"}""") }

        response shouldMatch ("POST" to "/sync")
    }

    private suspend infix fun HttpResponse.shouldMatch(operation: Pair<String, String>) {
        val (method, path) = operation
        BrewhouseSpec.documents(method, path, status.value) shouldBe true
        BrewhouseSpec.bodyProblems(method, path, status.value, bodyAsText()).orEmpty().shouldBeEmpty()
    }

    private data class Brewer(val key: String, val batchId: Long)

    private fun newBrewer(steps: List<MashStep>): Brewer {
        val user = UserRepository.create("contract-${UUID.randomUUID()}@beertool.test", "hash", "Brewer")
        val recipe = RecipeRepository.create(
            user.id,
            NewRecipe(
                name = "Pale",
                mashSteps = steps,
                fermentables = listOf(RecipeFermentable("Maris Otter", 4.5, FermentableType.MALT, usage = FermentableUsage.MASH)),
            ),
        )
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id))!!
        return Brewer(BrewhouseKeyRepository.issue(user.id), batch.id)
    }

    private fun session(batchId: Long) = Session(
        batchId = batchId, phase = Phase.MASH, stage = "Step 1 of 1", step = 0, targetC = 66.0, tempC = 64.0,
        heatPct = 75, stepLeftMin = 40.0, updatedAt = clock,
    )

    private fun contractApp(steps: List<MashStep> = listOf(MashStep(66.0, 60)), block: suspend ApplicationTestBuilder.(Brewer) -> Unit) =
        testApplication {
            application { configureBrewhouse(BrewhouseConfig()) { clock } }
            block(newBrewer(steps))
        }

    private fun HttpRequestBuilder.bearer(key: String) = header(HttpHeaders.Authorization, "Bearer $key")

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.sync(brewer: Brewer, report: Report) =
        post("$BREWHOUSE_PATH/sync") { bearer(brewer.key); json(encode(report, Report.serializer())) }
}
