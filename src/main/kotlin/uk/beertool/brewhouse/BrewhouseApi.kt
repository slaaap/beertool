package uk.beertool.brewhouse

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import uk.beertool.common.Env
import uk.beertool.common.flag
import uk.beertool.common.io
import uk.beertool.common.systemEnv
import uk.beertool.common.text
import uk.beertool.user.User
import uk.beertool.web.currentUser
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

const val BREWHOUSE_PATH = "/api/brewhouse/v1"
const val BREWHOUSE_WEB_PATH = "/api/brewhouse/web"
const val MAX_BODY_BYTES = 16 * 1024
const val MAX_WIDGET_BYTES = 256 * 1024

// The widget is the controller's code, so its frame gets an opaque origin, no network and only its own inline code.
const val WIDGET_POLICY = "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
    "img-src data:; font-src data:; form-action 'none'; base-uri 'none'; frame-ancestors 'self'"

data class BrewhouseConfig(
    val readingInterval: Duration = DEFAULT_READING_INTERVAL,
) {
    companion object {
        fun fromEnv(env: Env = systemEnv): BrewhouseConfig? {
            if (!env.flag("BREWHOUSE_ENABLED", default = false)) return null
            val seconds = env.text("BREWHOUSE_READING_INTERVAL_SECONDS")?.let {
                it.toLongOrNull()?.takeIf { s -> s > 0 } ?: error("BREWHOUSE_READING_INTERVAL_SECONDS must be a whole number of seconds above 0, not '$it'")
            }
            return BrewhouseConfig(readingInterval = seconds?.let { Duration.ofSeconds(it) } ?: DEFAULT_READING_INTERVAL)
        }
    }
}

// Whether the brewhouse is on, so Settings and the Brew Day page only offer it when it works.
object Brewhouse {
    @Volatile
    var service: BrewhouseService? = null

    val enabled get() = service != null
}

private val log = LoggerFactory.getLogger("uk.beertool.brewhouse.BrewhouseApi")

private val BrewhouseUser = AttributeKey<User>("BrewhouseUser")

fun Application.configureBrewhouse(
    config: BrewhouseConfig? = BrewhouseConfig.fromEnv(),
    now: () -> Instant = Instant::now,
) {
    if (config == null) {
        log.info("BREWHOUSE_ENABLED not set - brewhouse API disabled")
        return
    }
    val service = BrewhouseService(Brewhouses(now), config.readingInterval, now)
    val spec = Brewhouse::class.java.getResource("/brewhouse/openapi.yaml")!!.readText()
    routing {
        get("$BREWHOUSE_PATH/openapi.yaml") { call.respondText(spec, ContentType.parse("application/yaml")) }
        route(BREWHOUSE_PATH) {
            createChild(KeyProtected).apply {
                install(KeyAuth)
                keyRoutes(service)
            }
        }
        route(BREWHOUSE_WEB_PATH) { sessionRoutes(service) }
    }
    Brewhouse.service = service
    log.info("Brewhouse API enabled at {}, readings every {} s", BREWHOUSE_PATH, config.readingInterval.seconds)
}

private fun Route.keyRoutes(service: BrewhouseService) {
    post("sync") {
        call.api {
            val report = decode(call.receiveLimited(), Report.serializer())
            encode(service.sync(call.attributes[BrewhouseUser], report), SyncResponse.serializer())
        }
    }
    get("status") {
        call.api { encode(service.status(call.attributes[BrewhouseUser]), Status.serializer()) }
    }
    post("commands") {
        call.api(HttpStatusCode.Accepted) {
            val request = decode(call.receiveLimited(), CommandRequest.serializer())
            encode(service.queue(call.attributes[BrewhouseUser], request), Command.serializer())
        }
    }
    get("commands/{id}") {
        call.api { encode(service.command(call.attributes[BrewhouseUser], call.parameters["id"]!!), Command.serializer()) }
    }
    put("widget") {
        call.api(HttpStatusCode.NoContent) {
            if (call.request.contentType().withoutParameters() != ContentType.Text.Html) throw BrewhouseProblem(415, "Send the widget as text/html.")
            service.putWidget(call.attributes[BrewhouseUser], call.receiveLimited(MAX_WIDGET_BYTES))
            ""
        }
    }
    delete("widget") {
        call.api(HttpStatusCode.NoContent) { service.deleteWidget(call.attributes[BrewhouseUser]); "" }
    }
    get("batches/{batchId}/schedule") {
        call.api { encode(service.schedule(call.attributes[BrewhouseUser], call.batchId()), Schedule.serializer()) }
    }
    get("batches/{batchId}/readings") {
        call.api {
            val readings = service.readings(call.attributes[BrewhouseUser], call.batchId(), call.since())
            BrewhouseJson.encodeToString(ListSerializer(Reading.serializer()), readings)
        }
    }
}

// The Brew Day page's own routes: the signed-in brewer's session instead of a key, and nothing for showcase visitors.
private fun Route.sessionRoutes(service: BrewhouseService) {
    get("status") {
        call.asBrewer { encode(service.pageStatus(it), PageStatus.serializer()) }
    }
    post("commands") {
        call.asBrewer(HttpStatusCode.Accepted) {
            // A cross-site form cannot send JSON without a preflight, which on top of the SameSite cookie keeps this from being forged.
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                throw BrewhouseProblem(415, "Send the command as application/json.")
            }
            val request = decode(call.receiveLimited(), PageCommandRequest.serializer())
            encode(service.queue(it, request.toCommand(), StrikeInputs(request.grainTempC, request.mashThicknessLPerKg)), Command.serializer())
        }
    }
    get("widget") {
        val user = call.currentUser()?.takeIf { it.canWrite }
            ?: return@get call.respondError(HttpStatusCode.Unauthorized, "Log in to use the brewhouse.")
        val html = io { BrewhouseWidgetRepository.find(user.id)?.html }
            ?: return@get call.respondError(HttpStatusCode.NotFound, "The brewhouse hasn't uploaded a widget.")
        call.response.header("Content-Security-Policy", WIDGET_POLICY)
        call.response.header("X-Frame-Options", "SAMEORIGIN")
        call.response.header(HttpHeaders.CacheControl, "private, no-cache")
        call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    get("commands/{id}") {
        call.asBrewer { encode(service.command(it, call.parameters["id"]!!), Command.serializer()) }
    }
    get("batches/{batchId}/schedule") {
        call.asBrewer { encode(service.schedule(it, call.batchId()), Schedule.serializer()) }
    }
    get("batches/{batchId}/readings") {
        call.asBrewer { BrewhouseJson.encodeToString(ListSerializer(Reading.serializer()), service.readings(it, call.batchId(), call.since())) }
    }
}

private suspend fun ApplicationCall.asBrewer(success: HttpStatusCode = HttpStatusCode.OK, block: suspend (User) -> String) = api(success) {
    val user = currentUser()?.takeIf { it.canWrite } ?: throw BrewhouseProblem(401, "Log in to use the brewhouse.")
    block(user)
}

// A transparent node, so the key check covers the spec's routes but not the spec document beside them.
private object KeyProtected : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = "(brewhouse key)"
}

// Scoped to the routed API node, so no spelling of the URL reaches a handler without a key.
private val KeyAuth = createRouteScopedPlugin("BrewhouseKeyAuth") {
    onCall { call ->
        val token = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
            ?.ifEmpty { null }
        val user = token?.let { io { BrewhouseKeyRepository.userFor(it) } }
        if (user == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"beertool\"")
            call.respondError(HttpStatusCode.Unauthorized, "A valid brewhouse key is required.")
        } else {
            call.attributes.put(BrewhouseUser, user)
        }
    }
}

internal fun <T> decode(body: String, serializer: KSerializer<T>): T = try {
    BrewhouseJson.decodeFromString(serializer, body)
} catch (e: SerializationException) {
    throw BrewhouseProblem(400, "The body doesn't fit the API's schema: ${e.message.orEmpty().substringBefore("\nJSON input").substringBefore(" at path")}")
} catch (e: IllegalArgumentException) {
    throw BrewhouseProblem(400, "The body doesn't fit the API's schema: ${e.message.orEmpty()}")
}

internal fun <T> encode(value: T, serializer: KSerializer<T>): String = BrewhouseJson.encodeToString(serializer, value)

internal suspend fun ApplicationCall.receiveLimited(limit: Int = MAX_BODY_BYTES): String {
    request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { if (it > limit) throw tooLarge(limit) }
    val bytes = receiveChannel().readRemaining(limit + 1L).readByteArray()
    if (bytes.size > limit) throw tooLarge(limit)
    return bytes.decodeToString()
}

private fun tooLarge(limit: Int) = BrewhouseProblem(413, "The body is larger than ${limit / 1024} KiB.")

internal fun ApplicationCall.batchId(): Long =
    parameters["batchId"]?.toLongOrNull() ?: throw BrewhouseProblem(404, "No such batch.")

internal fun ApplicationCall.since(): Instant? = request.queryParameters["since"]?.let {
    try {
        Instant.parse(it)
    } catch (_: DateTimeParseException) {
        throw BrewhouseProblem(400, "'since' must be an ISO-8601 date-time, such as 2026-11-25T18:00:00Z.")
    }
}

internal suspend fun ApplicationCall.api(success: HttpStatusCode = HttpStatusCode.OK, block: suspend () -> String) {
    val body = try {
        io { block() }
    } catch (e: BrewhouseProblem) {
        return respondError(HttpStatusCode.fromValue(e.status), e.message.orEmpty())
    }
    if (success == HttpStatusCode.NoContent) respond(success) else respondText(body, ContentType.Application.Json, success)
}

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respondText(encode(ApiError(message), ApiError.serializer()), ContentType.Application.Json, status)
