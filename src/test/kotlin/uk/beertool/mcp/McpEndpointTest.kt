package uk.beertool.mcp

import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.UserRepository
import java.util.UUID

class McpEndpointTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    private fun mcpApp(config: McpConfig = McpConfig(publicUrl = "https://beer.example"), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { configureMcp(config) }
            block()
        }

    private suspend fun HttpClient.rpc(body: String, token: String?): HttpResponse = post(MCP_PATH) {
        header(HttpHeaders.Host, "beer.example")
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun newToken(): Pair<Long, String> {
        val user = UserRepository.create("mcp-${UUID.randomUUID()}@beertool.test", "hash", "Brewer")
        return user.id to ApiTokenRepository.create(user.id, "test")
    }

    @Test
    fun `should turn away a request without a valid token`() = mcpApp {
        val response = client.rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = "bt_not-a-token")

        response.status shouldBe HttpStatusCode.Unauthorized
        response.headers[HttpHeaders.WWWAuthenticate]!! shouldStartWith "Bearer"
    }

    @Test
    fun `should demand a token however the endpoint's address is spelled`() = mcpApp {
        val response = client.post("/%6Dcp") {
            header(HttpHeaders.Host, "beer.example")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should refuse a personal token once tokens are switched off`() = mcpApp(McpConfig("https://beer.example", tokensEnabled = false)) {
        val (_, token) = newToken()

        val response = client.rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token)

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `should offer no sign-in metadata once OAuth is switched off`() = mcpApp(McpConfig("https://beer.example", oauthEnabled = false)) {
        val challenge = client.rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = null)
        val metadata = client.get("/.well-known/oauth-authorization-server")

        challenge.headers[HttpHeaders.WWWAuthenticate]!! shouldNotContain "resource_metadata"
        metadata.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `should leave the endpoint off when nobody could sign in`() =
        mcpApp(McpConfig("https://beer.example", tokensEnabled = false, oauthEnabled = false)) {
            val response = client.rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = null)

            response.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `should refuse a request addressed to a host it does not serve`() = mcpApp {
        val (_, token) = newToken()

        val response = client.post(MCP_PATH) {
            header(HttpHeaders.Host, "evil.example")
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `should offer the recipe tools to a client with a valid token`() = mcpApp {
        val (_, token) = newToken()

        val response = client.rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token)

        response.status shouldBe HttpStatusCode.OK
        val names = Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        names shouldContainAll listOf("get_brewer_profile", "search_recipes", "get_recipe", "estimate_recipe", "create_recipe", "update_recipe")
    }

    @Test
    fun `should answer a tool call with the token owner's data`() = mcpApp {
        val (userId, token) = newToken()
        RecipeRepository.create(userId, NewRecipe(name = "Endpoint Stout"))

        val response = client.rpc(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_recipes","arguments":{"query":"stout"}}}""",
            token,
        )

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "Endpoint Stout"
    }

    @Test
    fun `should explain a malformed argument without exposing internals`() = mcpApp {
        val (_, token) = newToken()

        val response = client.rpc(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"estimate_recipe","arguments":{"recipe":{"name":"X","fermentables":"lots"}}}}""",
            token,
        )

        val text = response.bodyAsText()
        text shouldContain "don't fit the tool's input schema"
        text shouldNotContain "JSON input"
    }

    @Test
    fun `should report a bad draft as a tool error the agent can read`() = mcpApp {
        val (_, token) = newToken()

        val response = client.rpc(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"estimate_recipe","arguments":{"recipe":{"name":""}}}}""",
            token,
        )

        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        result["isError"]!!.jsonPrimitive.boolean.shouldBeTrue()
        result.toString() shouldContain "name is required"
    }
}
