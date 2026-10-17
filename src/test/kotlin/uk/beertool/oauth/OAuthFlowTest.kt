package uk.beertool.oauth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.auth.PasswordHasher
import uk.beertool.auth.configureSecurity
import uk.beertool.db.TestDatabase
import uk.beertool.mcp.McpConfig
import uk.beertool.mcp.configureMcp
import uk.beertool.user.UserRepository
import uk.beertool.web.SingleUser
import uk.beertool.web.configureRouting
import java.util.UUID

class OAuthFlowTest {

    private val issuer = "https://beer.example"
    private val resource = "$issuer/mcp"
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val claude = OAuthClient("https://claude.ai/oauth/test-client", "Claude", listOf("https://claude.ai/api/mcp/auth_callback"))

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
    }

    @AfterEach
    fun tearDown() {
        SingleUser.email = null
    }

    private fun oauthApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureRouting()
            configureMcp(McpConfig(issuer)) { ClientRegistry { id -> claude.takeIf { it.clientId == id } } }
        }
        block(createClient { install(HttpCookies); followRedirects = false })
    }

    private fun authorizeParams(clientId: String = claude.clientId, redirectUri: String = claude.redirectUris.single()) = listOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "code_challenge" to pkceChallenge(verifier),
        "code_challenge_method" to "S256",
        "state" to "xyz",
        "scope" to SCOPE,
        "resource" to resource,
    )

    private suspend fun HttpClient.logIn(): String {
        val email = "flow-${UUID.randomUUID()}@beertool.test"
        UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        post("/login") { contentType(ContentType.Application.FormUrlEncoded); setBody("email=$email&password=secret123") }
        return email
    }

    private suspend fun HttpClient.form(path: String, params: List<Pair<String, String>>): HttpResponse = post(path) {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(params.formUrlEncode())
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `should point an unauthenticated client to the metadata that leads to the login`() = oauthApp { client ->
        val challenge = client.post("/mcp") { header(HttpHeaders.Host, "beer.example") }
        val metadataUrl = Regex("resource_metadata=\"([^\"]+)\"").find(challenge.headers[HttpHeaders.WWWAuthenticate]!!)!!.groupValues[1]
        val protectedResource = json(client.get(Url(metadataUrl).encodedPath).bodyAsText())
        val server = json(client.get("/.well-known/oauth-authorization-server").bodyAsText())

        challenge.status shouldBe HttpStatusCode.Unauthorized
        protectedResource["resource"]!!.jsonPrimitive.content shouldBe resource
        protectedResource["authorization_servers"]!!.jsonArray.single().jsonPrimitive.content shouldBe issuer
        server["code_challenge_methods_supported"].toString() shouldContain "S256"
        server["token_endpoint_auth_methods_supported"].toString() shouldContain "none"
        server["client_id_metadata_document_supported"]!!.jsonPrimitive.content shouldBe "true"
    }

    @Test
    fun `should connect Claude end to end, from consent through code and tokens to an MCP call`() = oauthApp { client ->
        client.logIn()

        val consent = client.get("/oauth/authorize?" + authorizeParams().formUrlEncode())
        val approved = client.form("/oauth/authorize", authorizeParams() + ("decision" to "allow"))
        val callback = Url(approved.headers[HttpHeaders.Location]!!)
        val tokens = json(
            client.form(
                "/oauth/token",
                listOf(
                    "grant_type" to "authorization_code",
                    "code" to callback.parameters["code"]!!,
                    "redirect_uri" to claude.redirectUris.single(),
                    "client_id" to claude.clientId,
                    "code_verifier" to verifier,
                    "resource" to resource,
                ),
            ).bodyAsText(),
        )
        val tools = client.post("/mcp") {
            header(HttpHeaders.Host, "beer.example")
            header(HttpHeaders.Authorization, "Bearer ${tokens["access_token"]!!.jsonPrimitive.content}")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }

        consent.bodyAsText() shouldContain "Connect Claude?"
        callback.toString() shouldStartWith "https://claude.ai/api/mcp/auth_callback?"
        callback.parameters["state"] shouldBe "xyz"
        callback.parameters["iss"] shouldBe issuer
        tokens["token_type"]!!.jsonPrimitive.content shouldBe "Bearer"
        tools.status shouldBe HttpStatusCode.OK
        tools.bodyAsText() shouldContain "estimate_recipe"
    }

    @Test
    fun `should send a brewer who is not logged in to log in and then back to the consent page`() = oauthApp { client ->
        val authorizePath = "/oauth/authorize?" + authorizeParams().formUrlEncode()

        val firstVisit = client.get(authorizePath)
        val email = "back-${UUID.randomUUID()}@beertool.test"
        UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        val loggedIn = client.form("/login", listOf("email" to email, "password" to "secret123", "next" to authorizePath))

        firstVisit.headers[HttpHeaders.Location]!! shouldStartWith "/login?next="
        loggedIn.headers[HttpHeaders.Location] shouldBe authorizePath
    }

    @Test
    fun `should not let a showcase visitor approve access`() = oauthApp { client ->
        val owner = "owner-${UUID.randomUUID()}@beertool.test"
        UserRepository.create(owner, PasswordHasher.hash("secret123"), "Owner")
        SingleUser.email = owner

        val visit = client.get("/oauth/authorize?" + authorizeParams().formUrlEncode())
        val approve = client.form("/oauth/authorize", authorizeParams() + ("decision" to "allow"))

        visit.headers[HttpHeaders.Location]!! shouldStartWith "/login"
        approve.headers[HttpHeaders.Location] shouldBe "/login"
    }

    @Test
    fun `should hand a denial back to the app`() = oauthApp { client ->
        client.logIn()

        val denied = client.form("/oauth/authorize", authorizeParams() + ("decision" to "deny"))

        val callback = Url(denied.headers[HttpHeaders.Location]!!)
        callback.parameters["error"] shouldBe "access_denied"
        callback.parameters["code"].shouldBeNull()
        callback.parameters["state"] shouldBe "xyz"
    }

    @Test
    fun `should stop unknown apps and unregistered redirects on beertool's own page`() = oauthApp { client ->
        client.logIn()

        val stranger = client.get("/oauth/authorize?" + authorizeParams(clientId = "https://evil.example/client.json").formUrlEncode())
        val diverted = client.get("/oauth/authorize?" + authorizeParams(redirectUri = "https://evil.example/callback").formUrlEncode())

        stranger.status shouldBe HttpStatusCode.BadRequest
        stranger.headers[HttpHeaders.Location].shouldBeNull()
        diverted.status shouldBe HttpStatusCode.BadRequest
        diverted.headers[HttpHeaders.Location].shouldBeNull()
        diverted.bodyAsText() shouldNotContain "evil.example/callback?code"
    }

    @Test
    fun `should answer bad token requests with standard OAuth errors`() = oauthApp { client ->
        val unknownCode = client.form(
            "/oauth/token",
            listOf("grant_type" to "authorization_code", "code" to "btc_nope", "redirect_uri" to "x", "client_id" to claude.clientId, "code_verifier" to verifier),
        )
        val wrongGrant = client.form("/oauth/token", listOf("grant_type" to "password", "client_id" to claude.clientId))

        unknownCode.status shouldBe HttpStatusCode.BadRequest
        json(unknownCode.bodyAsText())["error"]!!.jsonPrimitive.content shouldBe "invalid_grant"
        json(wrongGrant.bodyAsText())["error"]!!.jsonPrimitive.content shouldBe "unsupported_grant_type"
        unknownCode.headers[HttpHeaders.CacheControl] shouldBe "no-store"
    }
}
