package uk.beertool.oauth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClientsTest {

    private val claudeCode = OAuthClient(
        clientId = "https://claude.ai/oauth/claude-code-client-metadata",
        name = "Claude Code",
        redirectUris = listOf("http://localhost/callback", "http://127.0.0.1/callback"),
    )
    private val claudeWeb = OAuthClient(
        clientId = "https://claude.ai/oauth/mcp-client-metadata",
        name = "Claude",
        redirectUris = listOf("https://claude.ai/api/mcp/auth_callback", "http://evil.example/callback"),
    )

    @Test
    fun `should accept a loopback redirect on whatever port the native client picked`() {
        val ports = listOf("http://localhost:3118/callback", "http://127.0.0.1:50007/callback")

        val allowed = ports.map { claudeCode.allowsRedirect(it) }

        allowed shouldBe listOf(true, true)
    }

    @Test
    fun `should not let a loopback redirect wander to another path or host`() {
        val attempts = listOf("http://localhost:3118/other", "http://localhost.evil.example:3118/callback", "http://localhost:3118/callback?x=1")

        val allowed = attempts.map { claudeCode.allowsRedirect(it) }

        allowed shouldBe listOf(false, false, false)
    }

    @Test
    fun `should match a hosted redirect exactly and refuse plain http even when registered`() {
        val attempts = listOf("https://claude.ai/api/mcp/auth_callback", "https://claude.ai/api/mcp/auth_callback/x", "http://evil.example/callback")

        val allowed = attempts.map { claudeWeb.allowsRedirect(it) }

        allowed shouldBe listOf(true, false, false)
    }

    @Test
    fun `should only fetch client documents from trusted https hosts`() {
        val clients = MetadataDocumentClients(setOf("claude.ai"))
        val ids = listOf(
            "https://claude.ai/oauth/claude-code-client-metadata",
            "http://claude.ai/oauth/claude-code-client-metadata",
            "https://evil.example/client.json",
            "https://claude.ai.evil.example/client.json",
            "https://claude.ai/",
            "https://user@claude.ai/client.json",
        )

        val trusted = ids.map { clients.isTrustedClientId(it) }

        trusted shouldBe listOf(true, false, false, false, false, false)
        clients.close()
    }

    @Test
    fun `should compute the PKCE S256 challenge from the RFC 7636 example`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        val challenge = pkceChallenge(verifier)

        challenge shouldBe "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }
}
