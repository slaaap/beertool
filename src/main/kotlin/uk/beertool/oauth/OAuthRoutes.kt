package uk.beertool.oauth

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import uk.beertool.user.User
import uk.beertool.web.ButtonStyle
import uk.beertool.web.actionBar
import uk.beertool.web.currentUser
import uk.beertool.web.page
import java.net.URI

const val SCOPE = "beertool"
private val SUPPORTED_SCOPES = setOf(SCOPE, "offline_access")

data class OAuthConfig(val issuer: String, val mcpPath: String) {
    val resource get() = issuer + mcpPath
    val resourceMetadataUrl get() = "$issuer/.well-known/oauth-protected-resource$mcpPath"
}

fun Route.oauthRoutes(config: OAuthConfig, clients: ClientRegistry) {
    val protectedResource = buildJsonObject {
        put("resource", config.resource)
        putJsonArray("authorization_servers") { add(JsonPrimitive(config.issuer)) }
        putJsonArray("scopes_supported") { add(JsonPrimitive(SCOPE)) }
        putJsonArray("bearer_methods_supported") { add(JsonPrimitive("header")) }
        put("resource_name", "beertool")
    }.toString()

    get("/.well-known/oauth-protected-resource${config.mcpPath}") { call.respondJson(protectedResource) }
    get("/.well-known/oauth-protected-resource") { call.respondJson(protectedResource) }

    val authorizationServer = buildJsonObject {
        put("issuer", config.issuer)
        put("authorization_endpoint", "${config.issuer}/oauth/authorize")
        put("token_endpoint", "${config.issuer}/oauth/token")
        putJsonArray("response_types_supported") { add(JsonPrimitive("code")) }
        putJsonArray("grant_types_supported") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
        putJsonArray("code_challenge_methods_supported") { add(JsonPrimitive("S256")) }
        putJsonArray("token_endpoint_auth_methods_supported") { add(JsonPrimitive("none")) }
        put("client_id_metadata_document_supported", true)
        putJsonArray("scopes_supported") { SUPPORTED_SCOPES.forEach { add(JsonPrimitive(it)) } }
        put("authorization_response_iss_parameter_supported", true)
    }.toString()

    get("/.well-known/oauth-authorization-server") { call.respondJson(authorizationServer) }

    get("/oauth/authorize") {
        val request = call.parseAuthorization(call.request.queryParameters, config, clients) ?: return@get
        val user = call.currentUser()
        if (user == null || !user.canWrite) {
            call.respondRedirect("/login?next=${call.request.uri.encodeURLParameter()}")
            return@get
        }
        call.respondHtml { consentPage(user, request) }
    }

    post("/oauth/authorize") {
        val params = call.receiveParameters()
        val request = call.parseAuthorization(params, config, clients) ?: return@post
        val user = call.currentUser()
        if (user == null || !user.canWrite) {
            call.respondRedirect("/login")
            return@post
        }
        if (params["decision"] != "allow") {
            call.redirectBack(request, config, "error" to "access_denied")
            return@post
        }
        val code = withContext(Dispatchers.IO) { OAuthStore.issueCode(user.id, request.request) }
        call.redirectBack(request, config, "code" to code)
    }

    post("/oauth/token") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val params = call.receiveParameters()
        val clientId = params["client_id"]
        try {
            if (clientId.isNullOrBlank()) throw OAuthException("invalid_request", "client_id is required.")
            val tokens = withContext(Dispatchers.IO) {
                when (params["grant_type"]) {
                    "authorization_code" -> OAuthStore.redeemCode(
                        code = params.required("code"),
                        clientId = clientId,
                        redirectUri = params.required("redirect_uri"),
                        verifier = params.required("code_verifier"),
                        resource = params["resource"]?.let { canonical(it) },
                    )
                    "refresh_token" -> OAuthStore.refresh(params.required("refresh_token"), clientId)
                    else -> throw OAuthException("unsupported_grant_type", "Use authorization_code or refresh_token.")
                }
            }
            call.respondJson(
                buildJsonObject {
                    put("access_token", tokens.accessToken)
                    put("token_type", "Bearer")
                    put("expires_in", tokens.expiresIn)
                    put("refresh_token", tokens.refreshToken)
                    put("scope", tokens.scope)
                }.toString(),
            )
        } catch (e: OAuthException) {
            call.respondJson(
                buildJsonObject { put("error", e.error); put("error_description", e.message) }.toString(),
                HttpStatusCode.BadRequest,
            )
        }
    }
}

private class ParsedAuthorization(val request: AuthorizationRequest, val state: String?)

private fun Parameters.required(name: String) =
    this[name]?.takeIf { it.isNotBlank() } ?: throw OAuthException("invalid_request", "$name is required.")

// Scheme and host compare case-insensitively and a trailing slash is ignored (RFC 8707 canonical URIs).
private fun canonical(uri: String): String {
    val u = runCatching { URI(uri) }.getOrNull() ?: return uri
    val port = if (u.port == -1) "" else ":${u.port}"
    return "${u.scheme?.lowercase()}://${u.host?.lowercase()}$port${u.rawPath.orEmpty().trimEnd('/')}"
}

private sealed interface Authorization {
    class Ok(val parsed: ParsedAuthorization) : Authorization
    class Problem(val message: String) : Authorization
    class Refused(val redirectUri: String, val state: String?, val error: String, val description: String) : Authorization
}

// Until the client and its redirect URI are verified, errors stay on beertool's page so they can't bounce users elsewhere.
private suspend fun checkAuthorization(params: Parameters, config: OAuthConfig, clients: ClientRegistry): Authorization {
    val client = params["client_id"]?.let { clients.find(it) }
        ?: return Authorization.Problem("This app isn't recognised, so beertool can't connect it. Only trusted apps such as Claude and ChatGPT can connect.")
    val redirectUri = params["redirect_uri"]?.takeIf { client.allowsRedirect(it) }
        ?: return Authorization.Problem("${client.name} asked to send you somewhere it hasn't registered, so the request was stopped.")
    val state = params["state"]
    fun refuse(error: String, description: String) = Authorization.Refused(redirectUri, state, error, description)

    val requestedScopes = params["scope"]?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    val challenge = params["code_challenge"]
    val resource = params["resource"]
    return when {
        params["response_type"] != "code" -> refuse("unsupported_response_type", "Only response_type=code is supported.")
        challenge.isNullOrBlank() || params["code_challenge_method"] != "S256" ->
            refuse("invalid_request", "PKCE with code_challenge_method=S256 is required.")
        requestedScopes.any { it !in SUPPORTED_SCOPES } -> refuse("invalid_scope", "Supported scopes: ${SUPPORTED_SCOPES.joinToString(" ")}.")
        resource != null && canonical(resource) != config.resource -> refuse("invalid_target", "This server only issues tokens for ${config.resource}.")
        else -> Authorization.Ok(ParsedAuthorization(AuthorizationRequest(client, redirectUri, challenge, SCOPE, config.resource), state))
    }
}

private suspend fun ApplicationCall.parseAuthorization(params: Parameters, config: OAuthConfig, clients: ClientRegistry): ParsedAuthorization? =
    when (val result = checkAuthorization(params, config, clients)) {
        is Authorization.Ok -> result.parsed
        is Authorization.Problem -> null.also { respondProblem(result.message) }
        is Authorization.Refused -> null.also {
            redirectTo(result.redirectUri, result.state, config, listOf("error" to result.error, "error_description" to result.description))
        }
    }

private suspend fun ApplicationCall.redirectBack(parsed: ParsedAuthorization, config: OAuthConfig, vararg query: Pair<String, String>) =
    redirectTo(parsed.request.redirectUri, parsed.state, config, query.toList())

private suspend fun ApplicationCall.redirectTo(redirectUri: String, state: String?, config: OAuthConfig, query: List<Pair<String, String>>) {
    val url = URLBuilder(redirectUri).apply {
        query.forEach { (k, v) -> parameters.append(k, v) }
        state?.let { parameters.append("state", it) }
        parameters.append("iss", config.issuer)
    }.buildString()
    respondRedirect(url)
}

private suspend fun ApplicationCall.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json, ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondProblem(message: String) = respondHtml(HttpStatusCode.BadRequest) {
    page("Can't connect") {
        div("auth-card") {
            h1 { +"Can't connect" }
            p { +message }
        }
    }
}

private fun HTML.consentPage(user: User, parsed: ParsedAuthorization) {
    val request = parsed.request
    val client = request.client
    page("Connect ${client.name}", user) {
        div("auth-card") {
            h1 { +"Connect ${client.name}?" }
            p { +"${client.name} wants to use the beertool account of "; strong { +user.displayName }; +"." }
            p { +"It will be able to:" }
            ul {
                li { +"read your recipes and brew log" }
                li { +"create recipes and change existing ones" }
            }
            p("muted") {
                +"App: "; strong { +client.hostname }
                br
                +"Access will be sent to: "; strong { +URI(request.redirectUri).host }
            }
            if (client.loopbackOnly) {
                p("alert") { +"This sends access to a program on your own computer. Allow it only if you just started the connection yourself, from Claude Code for example." }
            }
            form(action = "/oauth/authorize", method = FormMethod.post) {
                listOf(
                    "response_type" to "code",
                    "client_id" to client.clientId,
                    "redirect_uri" to request.redirectUri,
                    "code_challenge" to request.codeChallenge,
                    "code_challenge_method" to "S256",
                    "scope" to request.scope,
                    "resource" to request.resource,
                ).forEach { (name, value) -> hiddenInput(name = name) { this.value = value } }
                parsed.state?.let { hiddenInput(name = "state") { value = it } }
                actionBar {
                    button(type = ButtonType.submit, name = "decision", classes = "btn ${ButtonStyle.PRIMARY.css}") { value = "allow"; +"Allow" }
                    button(type = ButtonType.submit, name = "decision", classes = "btn") { value = "deny"; +"Deny" }
                }
            }
        }
    }
}
