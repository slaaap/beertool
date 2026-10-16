package uk.beertool.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import uk.beertool.user.User
import java.net.URI

const val MCP_PATH = "/mcp"

data class McpConfig(val publicUrl: String?) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): McpConfig? {
            if (env("MCP_ENABLED")?.trim()?.equals("true", ignoreCase = true) != true) return null
            return McpConfig(env("PUBLIC_URL")?.trim()?.trimEnd('/')?.ifBlank { null })
        }
    }
}

// Whether the endpoint is on, so Settings only offers access tokens when they can be used.
object Mcp {
    @Volatile
    var enabled = false

    @Volatile
    var publicUrl: String? = null
}

private val log = LoggerFactory.getLogger("uk.beertool.mcp.McpServer")

private val McpUser = AttributeKey<User>("McpUser")

private val INSTRUCTIONS = """
    beertool holds this brewer's beer recipes and brew log, and does the brewing maths.
    - Call get_brewer_profile before drafting, so volumes, efficiency and the default mash fit their kit.
    - Never compute OG, FG, ABV, IBU or colour yourself: call estimate_recipe, which uses the same formulas as the app.
    - Iterate with estimate_recipe; only call create_recipe or update_recipe once the brewer has agreed to save.
    - update_recipe replaces the whole recipe: start from get_recipe and send back every ingredient you want to keep.
    - Units: kg for fermentables, g for hops, litres, °C, minutes. Efficiency and attenuation are percentages.
    - Use BJCP 2021 style names from find_styles, so the recipe links to its style guideline.
""".trimIndent()

fun Application.configureMcp(config: McpConfig? = McpConfig.fromEnv()) {
    if (config == null) {
        log.info("MCP_ENABLED not set - MCP endpoint disabled")
        return
    }
    install(McpAuth)
    val hosts = listOfNotNull(config.publicUrl?.let { URI(it).host }) + listOf("localhost", "127.0.0.1", "[::1]")
    mcpStatelessStreamableHttp(path = MCP_PATH, allowedHosts = hosts) {
        beertoolMcpServer(call.attributes[McpUser], config.publicUrl)
    }
    Mcp.enabled = true
    Mcp.publicUrl = config.publicUrl
    log.info("MCP endpoint enabled at {}", MCP_PATH)
}

// Every MCP request carries a bearer token; each one gets a server bound to that token's user, so data never crosses users.
private val McpAuth = createApplicationPlugin("McpAuth") {
    onCall { call ->
        if (call.request.path() != MCP_PATH) return@onCall
        val token = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
        val user = token?.let { withContext(Dispatchers.IO) { ApiTokenRepository.userFor(it) } }
        if (user == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"beertool\"")
            call.respondText("A valid beertool access token is required.", status = HttpStatusCode.Unauthorized)
        } else {
            call.attributes.put(McpUser, user)
        }
    }
}

fun beertoolMcpServer(user: User, publicUrl: String?): Server {
    val server = Server(
        serverInfo = Implementation(name = "beertool", version = "1.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = INSTRUCTIONS,
    )
    val tools = RecipeTools(user, publicUrl)

    server.tool("get_brewer_profile", "The brewer's kit and defaults: volumes, efficiency, boil time, default mash, water ratios.", NO_ARGS) {
        tools.brewerProfile()
    }
    server.tool("search_recipes", "Find the brewer's recipes by name or ingredient, newest first, with estimated OG/ABV/IBU/colour and how often each was brewed.", SEARCH_ARGS) {
        tools.searchRecipes(it)
    }
    server.tool("get_recipe", "A whole recipe in the same shape create_recipe and update_recipe take, with its estimate and every brew of it.", RECIPE_NO_ARGS) {
        tools.getRecipe(it)
    }
    server.tool("list_brews", "The brew log, newest first: measured gravities, volumes, ABV, efficiency and notes.", BREWS_ARGS) {
        tools.listBrews(it)
    }
    server.tool("find_styles", "BJCP 2021 style names matching a query, with links to their guidelines.", STYLE_ARGS) {
        tools.findStyles(it)
    }
    server.tool("estimate_recipe", "Estimate OG, FG, ABV, IBU and colour for a draft recipe without saving it, with warnings about likely problems.", RECIPE_ARGS) {
        tools.estimateRecipe(it)
    }
    server.tool("create_recipe", "Save a new recipe. Returns its number and link. Only call once the brewer agreed to save.", RECIPE_ARGS, readOnly = false) {
        tools.createRecipe(it)
    }
    server.tool(
        "update_recipe",
        "Replace an existing recipe with the one given. Anything left out is removed, so start from get_recipe.",
        UPDATE_ARGS,
        readOnly = false,
        destructive = true,
    ) {
        tools.updateRecipe(it)
    }
    return server
}

private fun Server.tool(
    name: String,
    description: String,
    schema: ToolSchema,
    readOnly: Boolean = true,
    destructive: Boolean = false,
    run: (JsonObject) -> JsonElement,
) = addTool(
    name = name,
    description = description,
    inputSchema = schema,
    toolAnnotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive, openWorldHint = false),
) { request ->
    try {
        val result = withContext(Dispatchers.IO) { run(request.arguments ?: JsonObject(emptyMap())) }.withoutNulls()
        CallToolResult(content = listOf(TextContent(result.toString())))
    } catch (e: ToolError) {
        CallToolResult(content = listOf(TextContent(e.message.orEmpty())), isError = true)
    }
}
