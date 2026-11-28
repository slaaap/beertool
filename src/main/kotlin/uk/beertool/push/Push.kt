package uk.beertool.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import uk.beertool.batch.BatchRepository
import uk.beertool.brewhouse.Session
import uk.beertool.common.Env
import uk.beertool.common.flag
import uk.beertool.common.io
import uk.beertool.common.publicUrl
import uk.beertool.common.systemEnv
import uk.beertool.common.text
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.boilAdditions
import uk.beertool.user.User
import uk.beertool.web.currentUser
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

const val PUSH_PATH = "/push/subscriptions"

private val log = LoggerFactory.getLogger("uk.beertool.push.Push")

private val PushJson = Json { ignoreUnknownKeys = true }

data class PushConfig(val keys: VapidKeys, val subject: String) {
    companion object {
        fun fromEnv(env: Env = systemEnv): PushConfig? {
            if (!env.flag("PUSH_ENABLED", default = false)) return null
            val public = env.text("VAPID_PUBLIC_KEY") ?: error("PUSH_ENABLED needs VAPID_PUBLIC_KEY")
            val private = env.text("VAPID_PRIVATE_KEY") ?: error("PUSH_ENABLED needs VAPID_PRIVATE_KEY")
            // Apple refuses a token whose subject is not a URL or mailto:, and a page on beertool's own site says who sends.
            val subject = env.publicUrl()?.takeIf { it.startsWith("https://") }
                ?: error("Push notifications need an https PUBLIC_URL to name the sender")
            return PushConfig(VapidKeys.parse(public, private), subject)
        }
    }
}

object Push {
    @Volatile
    var notifier: PushNotifier? = null

    val enabled get() = notifier != null

    val publicKey get() = notifier?.publicKey
}

// The push service's answer is all that matters here, so the gateway is one call that tests can stand in for.
fun interface PushGateway {
    suspend fun send(endpoint: String, headers: Map<String, String>, body: ByteArray): Int
}

class HttpPushGateway : PushGateway, AutoCloseable {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    override suspend fun send(endpoint: String, headers: Map<String, String>, body: ByteArray): Int {
        val response = client.post(endpoint) {
            headers.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.OctetStream)
            setBody(body)
        }
        if (response.status.value !in 200..299) log.warn("Push to {} answered {}: {}", URI(endpoint).host, response.status.value, response.bodyAsText().take(200))
        return response.status.value
    }

    override fun close() = client.close()
}

@Serializable
private data class PushMessage(val title: String, val body: String, val tag: String, val url: String)

class PushNotifier(
    private val config: PushConfig,
    private val gateway: PushGateway = HttpPushGateway(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Instant = Instant::now,
) {
    private val sent = ConcurrentHashMap<String, Instant>()
    private val tokens = ConcurrentHashMap<String, Pair<String, Instant>>()

    val publicKey get() = config.keys.publicKeyText

    // Called on every brewhouse report; the batch and recipe are only looked up once something is worth saying.
    fun brewhouseReport(user: User, previous: Session?, current: Session?) {
        current ?: return
        val batchId = current.batchId ?: return
        val batch by lazy { BatchRepository.findById(batchId, user.id) }
        val recipe by lazy { batch?.let { RecipeRepository.findById(it.recipeId, user.id) } }
        val alerts = brewAlerts(previous, current, if (current.boilLeftMin != null || previous?.boilLeftMin != null) recipe?.boilAdditions().orEmpty() else emptyList())
        if (alerts.isEmpty()) return
        val b = batch ?: return
        val r = recipe ?: return
        val run = "$batchId:${current.startedAt}"
        alerts.forEach { alert ->
            if (sent.putIfAbsent("$run:${alert.key}", now()) == null) {
                notify(user.id, alert.title, alert.body, tag = "brew-${alert.key}", url = "/recipes/${r.no}/batches/${b.no}/brew")
            }
        }
        sent.values.removeIf { Duration.between(it, now()) > Duration.ofDays(1) }
    }

    fun notify(userId: Long, title: String, body: String, tag: String, url: String) {
        val payload = PushJson.encodeToString(PushMessage.serializer(), PushMessage(title, body, tag, url)).toByteArray()
        scope.launch {
            PushSubscriptionRepository.forUser(userId).forEach { send(it, payload) }
        }
    }

    internal suspend fun send(subscription: PushSubscription, payload: ByteArray) {
        val body = try {
            encryptPush(payload, unb64url(subscription.p256dh), unb64url(subscription.auth))
        } catch (e: IllegalArgumentException) {
            log.warn("Dropping a push subscription with unusable keys: {}", e.message)
            PushSubscriptionRepository.delete(subscription.endpoint)
            return
        }
        val headers = mapOf(
            "Authorization" to "vapid t=${token(origin(subscription.endpoint))}, k=${config.keys.publicKeyText}",
            "Content-Encoding" to "aes128gcm",
            // An alert is only worth having while it can still be acted on.
            "TTL" to "600",
            "Urgency" to "high",
        )
        val status = try {
            gateway.send(subscription.endpoint, headers, body)
        } catch (e: Exception) {
            log.warn("Push to {} failed: {}", URI(subscription.endpoint).host, e.message)
            return
        }
        // The browser dropped this subscription, so there is no one left to reach at it.
        if (status == 404 || status == 410) PushSubscriptionRepository.delete(subscription.endpoint)
    }

    // Apple asks for a token to be reused for at least an hour, and refuses one valid for more than a day.
    private fun token(audience: String): String {
        val at = now()
        tokens[audience]?.let { (token, expires) -> if (Duration.between(at, expires) > Duration.ofHours(1)) return token }
        val expires = at.plus(Duration.ofHours(12))
        return vapidJwt(config.keys, audience, config.subject, expires).also { tokens[audience] = it to expires }
    }

    private fun origin(endpoint: String) = URI(endpoint).let { "${it.scheme}://${it.authority}" }
}

@Serializable
data class SubscriptionKeys(val p256dh: String, val auth: String)

@Serializable
data class SubscriptionRequest(val endpoint: String, val keys: SubscriptionKeys? = null)

fun Application.configurePush(config: PushConfig? = PushConfig.fromEnv(), gateway: PushGateway? = null) {
    if (config == null) {
        log.info("PUSH_ENABLED not set - push notifications disabled")
        return
    }
    val notifier = if (gateway == null) PushNotifier(config) else PushNotifier(config, gateway)
    val worker = Push::class.java.getResource("/push/sw.js")!!.readText()
    routing {
        // A service worker only reaches the pages under its own path, so it is served from the root rather than /static.
        get("/sw.js") {
            call.response.header("Cache-Control", "no-cache")
            call.respondText(worker, ContentType.Text.JavaScript)
        }
        post(PUSH_PATH) {
            call.withSubscription { user, request ->
                val keys = request.keys ?: return@withSubscription call.respondText("A subscription needs its keys.", status = HttpStatusCode.BadRequest)
                val subscription = PushSubscription(request.endpoint, keys.p256dh, keys.auth)
                try {
                    unb64url(keys.p256dh).let(P256::publicKey)
                    require(unb64url(keys.auth).size == 16) { "The auth secret is 16 bytes." }
                } catch (e: IllegalArgumentException) {
                    return@withSubscription call.respondText("The subscription keys are not valid.", status = HttpStatusCode.BadRequest)
                }
                io { PushSubscriptionRepository.save(user.id, subscription) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
        delete(PUSH_PATH) {
            call.withSubscription { user, request ->
                io { PushSubscriptionRepository.delete(user.id, request.endpoint) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    Push.notifier = notifier
    log.info("Push notifications enabled")
}

// The browsers' own push services; beertool posts to nothing else, whatever a page sends it.
private val PUSH_HOSTS = listOf(".push.apple.com", "fcm.googleapis.com", ".push.services.mozilla.com", ".notify.windows.com")

internal fun isPushService(endpoint: String): Boolean {
    val uri = try { URI(endpoint) } catch (e: Exception) { return false }
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && uri.port == -1 && endpoint.length <= 1024 &&
        PUSH_HOSTS.any { if (it.startsWith(".")) host.endsWith(it) else host == it }
}

private suspend fun ApplicationCall.withSubscription(block: suspend (User, SubscriptionRequest) -> Unit) {
    val user = currentUser()?.takeIf { it.canWrite } ?: return respondText("Log in to get notifications.", status = HttpStatusCode.Unauthorized)
    // A cross-site form cannot send JSON without a preflight, which on top of the SameSite cookie keeps this from being forged.
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        return respondText("Send the subscription as application/json.", status = HttpStatusCode.UnsupportedMediaType)
    }
    val request = try {
        PushJson.decodeFromString(SubscriptionRequest.serializer(), receiveText())
    } catch (e: SerializationException) {
        return respondText("That is not a push subscription.", status = HttpStatusCode.BadRequest)
    } catch (e: IllegalArgumentException) {
        return respondText("That is not a push subscription.", status = HttpStatusCode.BadRequest)
    }
    if (!isPushService(request.endpoint)) return respondText("That is not a browser push service.", status = HttpStatusCode.BadRequest)
    block(user, request)
}
