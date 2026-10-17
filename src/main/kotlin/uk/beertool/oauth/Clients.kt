package uk.beertool.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class OAuthClient(
    val clientId: String,
    val name: String,
    val redirectUris: List<String>,
) {
    val hostname: String get() = URI(clientId).host

    fun allowsRedirect(uri: String): Boolean = isSafeRedirect(uri) && redirectUris.any { redirectMatches(it, uri) }

    val loopbackOnly get() = redirectUris.all { isLoopback(URI(it)) }
}

val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

private fun isLoopback(uri: URI) = uri.scheme == "http" && uri.host?.lowercase() in LOOPBACK_HOSTS

// Codes must only ever travel over HTTPS or stay on the user's own machine.
private fun isSafeRedirect(uri: String): Boolean {
    val u = runCatching { URI(uri) }.getOrNull() ?: return false
    return (u.scheme == "https" || isLoopback(u)) && u.rawFragment == null
}

// Native clients such as Claude Code listen on a fresh port each run, so a loopback redirect matches on any port (RFC 8252 §7.3).
internal fun redirectMatches(registered: String, requested: String): Boolean {
    if (registered == requested) return true
    val r = runCatching { URI(registered) }.getOrNull() ?: return false
    val q = runCatching { URI(requested) }.getOrNull() ?: return false
    return isLoopback(r) && isLoopback(q) && r.host.lowercase() == q.host.lowercase() &&
        r.rawPath == q.rawPath && q.rawQuery == null && q.rawFragment == null
}

fun interface ClientRegistry {
    suspend fun find(clientId: String): OAuthClient?
}

@Serializable
private class ClientMetadataDocument(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("redirect_uris") val redirectUris: List<String> = emptyList(),
)

private const val MAX_DOCUMENT_BYTES = 64 * 1024L
private val CACHE_FOR: Duration = Duration.ofHours(1)
private val REMEMBER_FAILURE_FOR: Duration = Duration.ofMinutes(5)
private const val MAX_CACHED = 200
private const val FETCHES_PER_MINUTE = 30

// Only documents on trusted hosts are fetched, so a stranger can't point the server at arbitrary or internal addresses.
class MetadataDocumentClients(private val trustedHosts: Set<String>) : ClientRegistry, AutoCloseable {
    private val log = LoggerFactory.getLogger(MetadataDocumentClients::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<OAuthClient?, Instant>>()
    private val budget = FetchBudget(FETCHES_PER_MINUTE)
    private val http = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }

    // Failures are remembered and fetches rationed, so anonymous requests can't hammer the trusted hosts from here.
    override suspend fun find(clientId: String): OAuthClient? {
        if (!isTrustedClientId(clientId)) return null
        val now = Instant.now()
        cache[clientId]?.let { (client, at) -> if (at.plus(if (client == null) REMEMBER_FAILURE_FOR else CACHE_FOR).isAfter(now)) return client }
        if (!budget.take(now)) return null
        val client = runCatching { fetch(clientId) }
            .onFailure { log.warn("Client metadata {} could not be fetched: {}", clientId, it.message) }
            .getOrNull()
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[clientId] = client to now
        return client
    }

    fun isTrustedClientId(clientId: String): Boolean {
        val uri = runCatching { URI(clientId) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in trustedHosts && !uri.rawPath.isNullOrEmpty() &&
            uri.rawPath != "/" && uri.rawFragment == null && uri.userInfo == null
    }

    // Streamed, so the size cap holds before the body is buffered.
    private suspend fun fetch(clientId: String): OAuthClient? = http.prepareGet(clientId).execute { response ->
        if (!response.status.isSuccess()) return@execute null
        val bytes = response.bodyAsChannel().readRemaining(MAX_DOCUMENT_BYTES + 1).readByteArray()
        if (bytes.size > MAX_DOCUMENT_BYTES) return@execute null
        val doc = json.decodeFromString(ClientMetadataDocument.serializer(), bytes.decodeToString())
        val redirects = doc.redirectUris.filter { runCatching { URI(it).host }.getOrNull() != null }
        if (doc.clientId != clientId || redirects.isEmpty()) return@execute null
        OAuthClient(clientId, doc.clientName?.takeIf { it.isNotBlank() } ?: URI(clientId).host, redirects)
    }

    override fun close() = http.close()
}

internal class FetchBudget(private val perMinute: Int) {
    private var windowStart = Instant.EPOCH
    private var used = 0

    @Synchronized
    fun take(now: Instant): Boolean {
        if (Duration.between(windowStart, now) >= Duration.ofMinutes(1)) {
            windowStart = now
            used = 0
        }
        return (used < perMinute).also { if (it) used++ }
    }
}
