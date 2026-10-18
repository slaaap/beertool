package uk.beertool.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import uk.beertool.common.Env
import uk.beertool.common.flag
import uk.beertool.common.io
import uk.beertool.common.publicUrl
import uk.beertool.common.systemEnv
import uk.beertool.common.text
import uk.beertool.oauth.ClientRegistry
import uk.beertool.oauth.LOOPBACK_HOSTS
import uk.beertool.oauth.MetadataDocumentClients
import uk.beertool.oauth.OAuthConfig
import uk.beertool.oauth.OAuthStore
import uk.beertool.oauth.oauthRoutes
import uk.beertool.user.User
import java.net.URI

const val MCP_PATH = "/mcp"

data class McpConfig(
    val publicUrl: String?,
    val trustedClientHosts: Set<String> = DEFAULT_CLIENT_HOSTS,
    val tokensEnabled: Boolean = true,
    val oauthEnabled: Boolean = true,
) {
    // OAuth needs an issuer address clients can reach, and the spec wants it on HTTPS outside a developer's machine.
    val oauth: OAuthConfig?
        get() = publicUrl?.takeIf { oauthEnabled && (it.startsWith("https://") || URI(it).host in LOOPBACK_HOSTS) }
            ?.let { OAuthConfig(it, MCP_PATH) }

    companion object {
        val DEFAULT_CLIENT_HOSTS = setOf("claude.ai", "chatgpt.com")

        fun fromEnv(env: Env = systemEnv): McpConfig? {
            if (!env.flag("MCP_ENABLED", default = false)) return null
            return McpConfig(
                publicUrl = env.publicUrl(),
                trustedClientHosts = env.text("MCP_OAUTH_CLIENT_HOSTS")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
                    ?: DEFAULT_CLIENT_HOSTS,
                tokensEnabled = env.flag("MCP_TOKENS_ENABLED", default = true),
                oauthEnabled = env.flag("MCP_OAUTH_ENABLED", default = true),
            )
        }
    }
}

// What the endpoint accepts, so Settings only offers the ways of connecting that work.
object Mcp {
    @Volatile
    var enabled = false

    @Volatile
    var publicUrl: String? = null

    @Volatile
    var tokensEnabled = false

    @Volatile
    var oauthEnabled = false
}

private val log = LoggerFactory.getLogger("uk.beertool.mcp.McpServer")

private val McpUser = AttributeKey<User>("McpUser")

private val INSTRUCTIONS = """
    beertool holds this brewer's beer recipes and brew log, and does the brewing maths.
    - Call get_brewer_profile before drafting, so volumes, efficiency and the default mash fit their kit.
    - Never compute OG, FG, ABV, IBU, colour, water minerals or mash pH yourself: call estimate_recipe, which uses the same formulas as the app.
    - Water: pick sources by name from the profile's waterSources, add salts in grams, an acid in ml (lactic; phosphoric or AMS for paler beers that need a lot, AMS also cutting the sparge water's alkalinity) and Campden tablets when tap water is used, and aim for the mash pH range estimate_recipe reports; its saltsFor gives the gypsum and calcium chloride for a bitter, balanced, malty or hazy water.
    - Iterate with estimate_recipe; only call create_recipe or update_recipe once the brewer has agreed to save.
    - update_recipe replaces the whole recipe: start from get_recipe and send back every ingredient you want to keep.
    - Units: kg for fermentables, g for hops, litres, °C, minutes. Efficiency and attenuation are percentages.
    - Use BJCP 2021 style names from find_styles, so the recipe links to its style guideline.
""".trimIndent()

fun Application.configureMcp(
    config: McpConfig? = McpConfig.fromEnv(),
    clientRegistry: (McpConfig) -> ClientRegistry = { MetadataDocumentClients(it.trustedClientHosts) },
) {
    if (config == null) {
        log.info("MCP_ENABLED not set - MCP endpoint disabled")
        return
    }
    val oauth = config.oauth
    if (!config.tokensEnabled && oauth == null) {
        log.warn("MCP endpoint disabled: personal tokens are off, and OAuth is off or lacks an HTTPS PUBLIC_URL, so nobody could connect")
        return
    }
    routing { route(MCP_PATH) { install(McpAuth) { this.oauth = oauth; tokensEnabled = config.tokensEnabled } } }
    if (oauth != null) {
        val clients = clientRegistry(config)
        routing { oauthRoutes(oauth, clients) }
        monitor.subscribe(ApplicationStopped) { (clients as? AutoCloseable)?.close() }
        log.info("MCP OAuth enabled for clients from {}", config.trustedClientHosts)
    }
    val hosts = listOfNotNull(config.publicUrl?.let { URI(it).host }) + LOOPBACK_HOSTS
    mcpStatelessStreamableHttp(path = MCP_PATH, allowedHosts = hosts) {
        beertoolMcpServer(call.attributes[McpUser], config.publicUrl)
    }
    Mcp.enabled = true
    Mcp.publicUrl = config.publicUrl
    Mcp.tokensEnabled = config.tokensEnabled
    Mcp.oauthEnabled = oauth != null
    log.info("MCP endpoint enabled at {}", MCP_PATH)
}

class McpAuthConfig {
    var oauth: OAuthConfig? = null
    var tokensEnabled = true
}

// Scoped to the routed /mcp node, so no spelling of the URL (such as /%6Dcp) reaches the tools without a token.
private val McpAuth = createRouteScopedPlugin("McpAuth", ::McpAuthConfig) {
    val oauth = pluginConfig.oauth
    val tokensEnabled = pluginConfig.tokensEnabled
    val challenge = "Bearer realm=\"beertool\"" + (oauth?.let { ", resource_metadata=\"${it.resourceMetadataUrl}\"" } ?: "")
    onCall { call ->
        val token = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
        val user = token?.let {
            io {
                (if (tokensEnabled) ApiTokenRepository.userFor(it) else null) ?: oauth?.let { o -> OAuthStore.userForAccessToken(it, o.resource) }
            }
        }
        if (user == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, challenge)
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

    server.tool("get_brewer_profile", "The brewer's kit and defaults: volumes, efficiency, boil time, default mash, water ratios and saved water sources.", NO_ARGS) {
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
    server.tool("estimate_recipe", "Estimate OG, FG, ABV, IBU and colour for a draft recipe without saving it, plus its water profile and mash pH, with warnings about likely problems.", RECIPE_ARGS) {
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

private fun toolError(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

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
        val result = io { run(request.arguments ?: JsonObject(emptyMap())) }.withoutNulls()
        CallToolResult(content = listOf(TextContent(result.toString())))
    } catch (e: ToolError) {
        toolError(e.message.orEmpty())
    } catch (e: SerializationException) {
        toolError("The arguments don't fit the tool's input schema: ${e.message.orEmpty().substringBefore("\nJSON input")}")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("MCP tool {} failed", name, e)
        toolError("beertool hit an internal error running $name.")
    }
}
