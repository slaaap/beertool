package uk.beertool.brewhouse

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
import uk.beertool.mcp.ApiTokenRepository
import uk.beertool.user.User
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

const val BREWHOUSE_PATH = "/api/brewhouse/v1"
const val MAX_BODY_BYTES = 16 * 1024

data class BrewhouseConfig(
    val readingInterval: Duration = DEFAULT_READING_INTERVAL,
    val tokensEnabled: Boolean = true,
) {
    companion object {
        fun fromEnv(env: Env = systemEnv): BrewhouseConfig? {
            if (!env.flag("BREWHOUSE_ENABLED", default = false)) return null
            val seconds = env.text("BREWHOUSE_READING_INTERVAL_SECONDS")?.let {
                it.toLongOrNull()?.takeIf { s -> s > 0 } ?: error("BREWHOUSE_READING_INTERVAL_SECONDS must be a whole number of seconds above 0, not '$it'")
            }
            return BrewhouseConfig(
                readingInterval = seconds?.let { Duration.ofSeconds(it) } ?: DEFAULT_READING_INTERVAL,
                tokensEnabled = env.flag("MCP_TOKENS_ENABLED", default = true),
            )
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
    if (!config.tokensEnabled) {
        log.warn("Brewhouse API disabled: MCP_TOKENS_ENABLED is false, and a controller can only connect with a personal access token")
        return
    }
    val service = BrewhouseService(Brewhouses(now), config.readingInterval, now)
    val spec = Brewhouse::class.java.getResource("/brewhouse/openapi.yaml")!!.readText()
    routing {
        get("$BREWHOUSE_PATH/openapi.yaml") { call.respondText(spec, ContentType.parse("application/yaml")) }
        route(BREWHOUSE_PATH) {
            createChild(TokenProtected).apply {
                install(TokenAuth)
                tokenRoutes(service)
            }
        }
    }
    Brewhouse.service = service
    log.info("Brewhouse API enabled at {}, readings every {} s", BREWHOUSE_PATH, config.readingInterval.seconds)
}

private fun Route.tokenRoutes(service: BrewhouseService) {
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

// A transparent node, so the token check covers the spec's routes but not the spec document beside them.
private object TokenProtected : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = "(brewhouse token)"
}

// Scoped to the routed API node, so no spelling of the URL reaches a handler without a token.
private val TokenAuth = createRouteScopedPlugin("BrewhouseTokenAuth") {
    onCall { call ->
        val token = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
            ?.ifEmpty { null }
        val user = token?.let { io { ApiTokenRepository.userFor(it) } }
        if (user == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"beertool\"")
            call.respondError(HttpStatusCode.Unauthorized, "A valid beertool access token is required.")
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

internal suspend fun ApplicationCall.receiveLimited(): String {
    request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { if (it > MAX_BODY_BYTES) throw tooLarge() }
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw tooLarge()
    return bytes.decodeToString()
}

private fun tooLarge() = BrewhouseProblem(413, "The body is larger than 16 KiB.")

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
    respondText(body, ContentType.Application.Json, success)
}

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respondText(encode(ApiError(message), ApiError.serializer()), ContentType.Application.Json, status)
