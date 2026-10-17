package uk.beertool.mcp

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class McpConfigTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    @Test
    fun `should stay off unless switched on`() {
        val config = McpConfig.fromEnv(env("PUBLIC_URL" to "https://beer.example"))

        config.shouldBeNull()
    }

    @Test
    fun `should let Claude and ChatGPT connect by default`() {
        val config = McpConfig.fromEnv(env("MCP_ENABLED" to "true", "PUBLIC_URL" to "https://beer.example/"))!!

        config.trustedClientHosts shouldBe setOf("claude.ai", "chatgpt.com")
        config.oauth!!.resource shouldBe "https://beer.example/mcp"
    }

    @Test
    fun `should take the trusted app hosts from the environment`() {
        val config = McpConfig.fromEnv(env("MCP_ENABLED" to "true", "MCP_OAUTH_CLIENT_HOSTS" to " Claude.ai , example.org,"))!!

        config.trustedClientHosts shouldBe setOf("claude.ai", "example.org")
    }

    @Test
    fun `should accept both ways of connecting unless one is switched off`() {
        val defaults = McpConfig.fromEnv(env("MCP_ENABLED" to "true", "PUBLIC_URL" to "https://beer.example"))!!
        val oauthOnly = McpConfig.fromEnv(env("MCP_ENABLED" to "true", "PUBLIC_URL" to "https://beer.example", "MCP_TOKENS_ENABLED" to "false"))!!
        val tokensOnly = McpConfig.fromEnv(env("MCP_ENABLED" to "true", "PUBLIC_URL" to "https://beer.example", "MCP_OAUTH_ENABLED" to "FALSE"))!!

        val methods = listOf(defaults, oauthOnly, tokensOnly).map { it.tokensEnabled to (it.oauth != null) }

        methods shouldBe listOf(true to true, false to true, true to false)
    }

    @Test
    fun `should offer OAuth only on HTTPS or a developer's own machine`() {
        val configs = listOf("https://beer.example", "http://localhost:8080", "http://beer.example")
            .map { McpConfig(publicUrl = it) }

        val oauth = configs.map { it.oauth != null }

        oauth shouldBe listOf(true, true, false)
    }
}
