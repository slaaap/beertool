package uk.beertool.brewhouse

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.db.TestDatabase
import uk.beertool.mcp.ApiTokenRepository
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

class BrewhouseApiTest {

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
    fun `should turn away a request without a token`() = brewhouseApp {
        val response = client.get("$BREWHOUSE_PATH/status")

        response.status shouldBe HttpStatusCode.Unauthorized
        response.headers[HttpHeaders.WWWAuthenticate]!! shouldStartWith "Bearer"
        decode(response.bodyAsText(), ApiError.serializer()).error shouldContain "brewhouse key"
    }

    @Test
    fun `should turn away an unknown key`() = brewhouseApp {
        val response = client.get("$BREWHOUSE_PATH/status") { bearer("bh_not-a-key") }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should turn away a personal access token, which only opens the AI assistant`() = brewhouseApp {
        val brewer = newBrewer()
        val personal = ApiTokenRepository.create(brewer.userId, "Claude Code")

        val response = client.get("$BREWHOUSE_PATH/status") { bearer(personal) }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should retire the old key when the brewer makes a new one`() = brewhouseApp {
        val brewer = newBrewer()
        val fresh = BrewhouseKeyRepository.issue(brewer.userId)

        val old = client.get("$BREWHOUSE_PATH/status") { bearer(brewer.token) }
        val new = client.get("$BREWHOUSE_PATH/status") { bearer(fresh) }

        old.status shouldBe HttpStatusCode.Unauthorized
        new.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `should turn away the brewhouse once disconnected`() = brewhouseApp {
        val brewer = newBrewer()
        BrewhouseKeyRepository.revoke(brewer.userId)

        val response = client.post("$BREWHOUSE_PATH/sync") { bearer(brewer.token); json(encode(report(), Report.serializer())) }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should demand a token however the API's address is spelled`() = brewhouseApp {
        val response = client.get("/api/brewhouse/v1/%73tatus")

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should serve its own spec without a token`() = brewhouseApp {
        val response = client.get("$BREWHOUSE_PATH/openapi.yaml")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "title: Beertool Brewhouse API"
    }

    @Test
    fun `should store a sync as the brewhouse's latest state`() = brewhouseApp {
        val brewer = newBrewer()
        val sent = report(software = "beertool-brewhouse 1.2")

        client.sync(brewer, sent).status shouldBe HttpStatusCode.OK
        val status = decode(client.get("$BREWHOUSE_PATH/status") { bearer(brewer.token) }.bodyAsText(), Status.serializer())

        status.online shouldBe true
        status.lastSeenAt shouldBe clock
        status.report shouldBe sent
    }

    @Test
    fun `should spell out the required nullable fields of a brewhouse that never synced`() = brewhouseApp {
        val brewer = newBrewer()

        val body = client.get("$BREWHOUSE_PATH/status") { bearer(brewer.token) }.bodyAsText()

        body shouldBe """{"online":false,"lastSeenAt":null,"report":null}"""
    }

    @Test
    fun `should hand a queued command to the brewhouse in its next sync`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report())

        val queued = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"pause"}""") }
        val synced = decode(client.sync(brewer, report()).bodyAsText(), SyncResponse.serializer())

        queued.status shouldBe HttpStatusCode.Accepted
        val command = decode(queued.bodyAsText(), Command.serializer())
        synced.commands.map { it.id } shouldBe listOf(command.id)
        queued.bodyAsText() shouldContain "\"status\":\"pending\""
    }

    @Test
    fun `should report the brewhouse's answer to a command`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report())
        val command = decode(client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"start_boil"}""") }.bodyAsText(), Command.serializer())

        client.sync(brewer, report(results = listOf(CommandResult(command.id, ok = false, error = "still mashing"))))
        val answer = decode(client.get("$BREWHOUSE_PATH/commands/${command.id}") { bearer(brewer.token) }.bodyAsText(), Command.serializer())

        answer.status shouldBe CommandStatus.REFUSED
        answer.error shouldBe "still mashing"
    }

    @Test
    fun `should not find a command queued by another brewer`() = brewhouseApp {
        val brewer = newBrewer()
        val other = newBrewer()
        client.sync(brewer, report())
        val command = decode(client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"stop"}""") }.bodyAsText(), Command.serializer())

        val response = client.get("$BREWHOUSE_PATH/commands/${command.id}") { bearer(other.token) }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `should refuse a command while the brewhouse is offline`() = brewhouseApp {
        val brewer = newBrewer()

        val response = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"pause"}""") }

        response.status shouldBe HttpStatusCode.Conflict
        decode(response.bodyAsText(), ApiError.serializer()).error shouldContain "offline"
    }

    @Test
    fun `should refuse to start a brewhouse that is not ready`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report(ready = false))

        val response = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"start","batchId":${brewer.batchId}}""") }

        response.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `should deliver a start with the schedule built from the batch`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report())

        val response = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"start","batchId":${brewer.batchId}}""") }

        response.status shouldBe HttpStatusCode.Accepted
        val schedule = decode(response.bodyAsText(), Command.serializer()).schedule.shouldNotBeNull()
        schedule.batchId shouldBe brewer.batchId
        schedule.grainKg shouldBe 4.5
        schedule.steps shouldBe listOf(ScheduleStep(66.0, 60))
    }

    @Test
    fun `should leave the schedule out of commands other than start`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report())

        val body = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"dough_in","batchId":${brewer.batchId}}""") }.bodyAsText()

        body shouldContain "\"type\":\"dough_in\""
        body shouldNotContain "schedule"
    }

    @Test
    fun `should refuse to start another brewer's batch`() = brewhouseApp {
        val brewer = newBrewer()
        val other = newBrewer()
        client.sync(brewer, report())

        val response = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"start","batchId":${other.batchId}}""") }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `should refuse a start without a batch`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report())

        val response = client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"start"}""") }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `should explain that a batch whose recipe has no mash steps cannot be scheduled`() = brewhouseApp {
        val brewer = newBrewer(steps = emptyList())

        val response = client.get("$BREWHOUSE_PATH/batches/${brewer.batchId}/schedule") { bearer(brewer.token) }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        decode(response.bodyAsText(), ApiError.serializer()).error shouldContain "mash steps"
    }

    @Test
    fun `should preview the same schedule a start would send`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report())
        val started = decode(
            client.post("$BREWHOUSE_PATH/commands") { bearer(brewer.token); json("""{"type":"start","batchId":${brewer.batchId}}""") }.bodyAsText(),
            Command.serializer(),
        )

        val preview = decode(client.get("$BREWHOUSE_PATH/batches/${brewer.batchId}/schedule") { bearer(brewer.token) }.bodyAsText(), Schedule.serializer())

        preview shouldBe started.schedule
    }

    @Test
    fun `should turn away a report larger than 16 KiB`() = brewhouseApp {
        val brewer = newBrewer()

        val response = client.sync(brewer, report(software = "x".repeat(MAX_BODY_BYTES)))

        response.status shouldBe HttpStatusCode.PayloadTooLarge
    }

    @Test
    fun `should reject a report with a phase the API does not know`() = brewhouseApp {
        val brewer = newBrewer()
        val body = encode(report(session = session(batchId = null)), Report.serializer()).replace("\"mash\"", "\"sparging\"")

        val response = client.post("$BREWHOUSE_PATH/sync") { bearer(brewer.token); json(body) }

        response.status shouldBe HttpStatusCode.BadRequest
        decode(response.bodyAsText(), ApiError.serializer()).error shouldContain "schema"
    }

    @Test
    fun `should reject a report missing a required field`() = brewhouseApp {
        val brewer = newBrewer()

        val response = client.post("$BREWHOUSE_PATH/sync") { bearer(brewer.token); json("""{"software":"x","session":null,"commandResults":[]}""") }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `should pass on why the brewhouse is not ready`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report(ready = false).copy(notReady = "thermometer offline"))

        val status = decode(client.get("$BREWHOUSE_PATH/status") { bearer(brewer.token) }.bodyAsText(), Status.serializer())

        status.report!!.notReady shouldBe "thermometer offline"
    }

    @Test
    fun `should reject a heat level outside 0 to 100 percent`() = brewhouseApp {
        val brewer = newBrewer()

        val response = client.sync(brewer, report(session = session(brewer.batchId).copy(heatPct = 140)))

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `should accept a report that still carries fields the API has dropped, such as devices`() = brewhouseApp {
        val brewer = newBrewer()

        val response = client.post("$BREWHOUSE_PATH/sync") {
            bearer(brewer.token)
            json("""{"software":"x","ready":true,"session":null,"commandResults":[],"devices":{"hob":{"online":true}}}""")
        }

        response.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `should keep at most one reading per interval for the session's batch`() = brewhouseApp {
        val brewer = newBrewer()

        client.sync(brewer, report(session = session(brewer.batchId, tempC = 64.0)))
        later(Duration.ofSeconds(2))
        client.sync(brewer, report(session = session(brewer.batchId, tempC = 64.5)))
        later(Duration.ofSeconds(8))
        client.sync(brewer, report(session = session(brewer.batchId, tempC = 65.5)))

        val readings = client.readings(brewer)
        readings.map { it.tempC } shouldBe listOf(64.0, 65.5)
        readings.first().phase shouldBe Phase.MASH
        readings.first().targetC shouldBe 66.0
        readings.first().heatPct shouldBe 75
    }

    @Test
    fun `should stop charting once the session is over, though the controller still reports it`() = brewhouseApp {
        val brewer = newBrewer()

        client.sync(brewer, report(session = session(brewer.batchId, tempC = 99.0)))
        listOf(Phase.DONE, Phase.FAULT, Phase.IDLE).forEach {
            later(Duration.ofSeconds(10))
            client.sync(brewer, report(session = session(brewer.batchId).copy(phase = it)))
        }

        client.readings(brewer).map { it.tempC } shouldBe listOf(99.0)
    }

    @Test
    fun `should return only the readings after the given instant`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report(session = session(brewer.batchId, tempC = 64.0)))
        val firstAt = clock
        later(Duration.ofSeconds(10))
        client.sync(brewer, report(session = session(brewer.batchId, tempC = 65.0)))

        val newer = client.readings(brewer, since = firstAt)

        newer.map { it.tempC } shouldBe listOf(65.0)
    }

    @Test
    fun `should record nothing against a batch the token's brewer does not own`() = brewhouseApp {
        val brewer = newBrewer()
        val other = newBrewer()

        client.sync(brewer, report(session = session(other.batchId)))

        client.readings(other).shouldBeEmpty()
    }

    @Test
    fun `should record readings at the configured interval`() = brewhouseApp(BrewhouseConfig(readingInterval = Duration.ofSeconds(30))) {
        val brewer = newBrewer()

        client.sync(brewer, report(session = session(brewer.batchId)))
        later(Duration.ofSeconds(10))
        client.sync(brewer, report(session = session(brewer.batchId)))
        later(Duration.ofSeconds(20))
        client.sync(brewer, report(session = session(brewer.batchId)))

        client.readings(brewer) shouldHaveSize 2
    }

    @Test
    fun `should delete a batch's readings with the batch`() = brewhouseApp {
        val brewer = newBrewer()
        client.sync(brewer, report(session = session(brewer.batchId)))

        BatchRepository.delete(brewer.batchId, brewer.userId)

        BrewhouseReadingRepository.forBatch(brewer.batchId).shouldBeEmpty()
    }

    @Test
    fun `should reject a since that is not a date-time`() = brewhouseApp {
        val brewer = newBrewer()

        val response = client.get("$BREWHOUSE_PATH/batches/${brewer.batchId}/readings?since=yesterday") { bearer(brewer.token) }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `should read the reading interval from the environment`() {
        val env = mapOf("BREWHOUSE_ENABLED" to "true", "BREWHOUSE_READING_INTERVAL_SECONDS" to "30")

        val config = BrewhouseConfig.fromEnv(env::get)

        config shouldBe BrewhouseConfig(readingInterval = Duration.ofSeconds(30))
    }

    @Test
    fun `should stay off unless switched on`() {
        val config = BrewhouseConfig.fromEnv(mapOf<String, String>()::get)

        config.shouldBeNull()
    }

    private data class Brewer(val userId: Long, val token: String, val batchId: Long)

    private fun newBrewer(steps: List<MashStep> = listOf(MashStep(66.0, 60))): Brewer {
        val user = UserRepository.create("brewhouse-${UUID.randomUUID()}@beertool.test", "hash", "Brewer")
        val recipe = RecipeRepository.create(
            user.id,
            NewRecipe(
                name = "Pale",
                mashSteps = steps,
                fermentables = listOf(
                    RecipeFermentable("Maris Otter", 4.5, FermentableType.MALT, usage = FermentableUsage.MASH),
                    RecipeFermentable("Dextrose", 0.4, FermentableType.SUGAR, usage = FermentableUsage.BOIL),
                ),
            ),
        )
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id))!!
        return Brewer(user.id, BrewhouseKeyRepository.issue(user.id), batch.id)
    }

    private fun session(batchId: Long?, tempC: Double = 64.0) = Session(
        batchId = batchId,
        phase = Phase.MASH,
        stage = "Step 1 of 1",
        step = 0,
        targetC = 66.0,
        tempC = tempC,
        heatPct = 75,
        updatedAt = clock,
    )

    private fun later(by: Duration) {
        clock = clock.plus(by)
    }

    private fun brewhouseApp(config: BrewhouseConfig = BrewhouseConfig(), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { configureBrewhouse(config) { clock } }
            block()
        }

    private fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.sync(brewer: Brewer, report: Report): HttpResponse =
        post("$BREWHOUSE_PATH/sync") { bearer(brewer.token); json(encode(report, Report.serializer())) }

    private suspend fun HttpClient.readings(brewer: Brewer, since: Instant? = null): List<Reading> {
        val query = since?.let { "?since=$it" }.orEmpty()
        val body = get("$BREWHOUSE_PATH/batches/${brewer.batchId}/readings$query") { bearer(brewer.token) }.bodyAsText()
        return decode(body, ListSerializer(Reading.serializer()))
    }
}
