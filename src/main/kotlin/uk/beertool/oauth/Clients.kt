package uk.beertool.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
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

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

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

/**
 * Clients identify themselves with an HTTPS URL that serves their metadata (OAuth Client ID Metadata Documents).
 * Only URLs on trusted hosts are fetched: fetching any URL a stranger supplies would let them aim the server at
 * internal addresses.
 */
class MetadataDocumentClients(private val trustedHosts: Set<String>) : ClientRegistry, AutoCloseable {
    private val log = LoggerFactory.getLogger(MetadataDocumentClients::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<OAuthClient, Instant>>()
    private val http = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }

    override suspend fun find(clientId: String): OAuthClient? {
        if (!isTrustedClientId(clientId)) return null
        cache[clientId]?.let { (client, fetchedAt) -> if (fetchedAt.plus(CACHE_FOR).isAfter(Instant.now())) return client }
        val client = runCatching { fetch(clientId) }
            .onFailure { log.warn("Client metadata {} could not be fetched: {}", clientId, it.message) }
            .getOrNull() ?: return null
        cache[clientId] = client to Instant.now()
        return client
    }

    fun isTrustedClientId(clientId: String): Boolean {
        val uri = runCatching { URI(clientId) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in trustedHosts && !uri.rawPath.isNullOrEmpty() &&
            uri.rawPath != "/" && uri.rawFragment == null && uri.userInfo == null
    }

    private suspend fun fetch(clientId: String): OAuthClient? {
        val response = http.get(clientId)
        if (!response.status.isSuccess()) return null
        val bytes = response.bodyAsChannel().readRemaining(MAX_DOCUMENT_BYTES + 1).readByteArray()
        if (bytes.size > MAX_DOCUMENT_BYTES) return null
        val doc = json.decodeFromString(ClientMetadataDocument.serializer(), bytes.decodeToString())
        if (doc.clientId != clientId || doc.redirectUris.isEmpty()) return null
        return OAuthClient(clientId, doc.clientName?.takeIf { it.isNotBlank() } ?: URI(clientId).host, doc.redirectUris)
    }

    override fun close() = http.close()
}
