package uk.beertool.oauth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.user.UserRepository
import java.time.Instant
import java.util.UUID

class OAuthStoreTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    private val resource = "https://beer.example/mcp"
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val client = OAuthClient("https://claude.ai/oauth/test", "Claude", listOf("https://claude.ai/api/mcp/auth_callback"))
    private val now = Instant.parse("2026-10-17T10:00:00Z")

    private fun request() = AuthorizationRequest(client, client.redirectUris.single(), pkceChallenge(verifier), SCOPE, resource)

    private fun newUser() = UserRepository.create("oauth-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id

    private fun redeem(code: String, verifier: String = this.verifier, at: Instant = now) =
        OAuthStore.redeemCode(code, client.clientId, client.redirectUris.single(), verifier, resource, at)

    @Test
    fun `should trade a code and its verifier for tokens that reach the brewer`() {
        val userId = newUser()
        val code = OAuthStore.issueCode(userId, request(), now)

        val tokens = redeem(code)

        OAuthStore.userForAccessToken(tokens.accessToken, resource, now).shouldNotBeNull().id shouldBe userId
        tokens.expiresIn shouldBe ACCESS_TTL.seconds
    }

    @Test
    fun `should burn a code on a wrong verifier so it cannot be tried again`() {
        val code = OAuthStore.issueCode(newUser(), request(), now)

        shouldThrow<OAuthException> { redeem(code, verifier = "x".repeat(43)) }.error shouldBe "invalid_grant"

        shouldThrow<OAuthException> { redeem(code) }.error shouldBe "invalid_grant"
    }

    @Test
    fun `should refuse an expired code`() {
        val code = OAuthStore.issueCode(newUser(), request(), now)

        val error = shouldThrow<OAuthException> { redeem(code, at = now.plus(CODE_TTL).plusSeconds(1)) }

        error.error shouldBe "invalid_grant"
    }

    @Test
    fun `should rotate refresh tokens and refuse the spent one`() {
        val tokens = redeem(OAuthStore.issueCode(newUser(), request(), now))

        val refreshed = OAuthStore.refresh(tokens.refreshToken, client.clientId, now)

        refreshed.refreshToken shouldNotBe tokens.refreshToken
        shouldThrow<OAuthException> { OAuthStore.refresh(tokens.refreshToken, client.clientId, now) }.error shouldBe "invalid_grant"
        OAuthStore.refresh(refreshed.refreshToken, client.clientId, now).accessToken.shouldNotBeNull()
    }

    @Test
    fun `should refuse an access token once it expires or when shown to another resource`() {
        val tokens = redeem(OAuthStore.issueCode(newUser(), request(), now))

        val expired = OAuthStore.userForAccessToken(tokens.accessToken, resource, now.plus(ACCESS_TTL))
        val elsewhere = OAuthStore.userForAccessToken(tokens.accessToken, "https://other.example/mcp", now)

        expired.shouldBeNull()
        elsewhere.shouldBeNull()
    }

    @Test
    fun `should cut off access and refresh when the brewer revokes the app`() {
        val userId = newUser()
        val tokens = redeem(OAuthStore.issueCode(userId, request(), now))
        val grant = OAuthStore.listGrants(userId).single()

        OAuthStore.revokeGrant(grant.id, userId)

        OAuthStore.userForAccessToken(tokens.accessToken, resource, now).shouldBeNull()
        shouldThrow<OAuthException> { OAuthStore.refresh(tokens.refreshToken, client.clientId, now) }
        OAuthStore.listGrants(userId).shouldBeEmpty()
    }

    @Test
    fun `should keep one grant per app when the brewer connects it again`() {
        val userId = newUser()
        redeem(OAuthStore.issueCode(userId, request(), now))

        redeem(OAuthStore.issueCode(userId, request(), now))

        OAuthStore.listGrants(userId) shouldHaveSize 1
    }

    @Test
    fun `should not let one brewer revoke another's app`() {
        val owner = newUser()
        val tokens = redeem(OAuthStore.issueCode(owner, request(), now))
        val grant = OAuthStore.listGrants(owner).single()

        val revoked = OAuthStore.revokeGrant(grant.id, newUser())

        revoked shouldBe false
        OAuthStore.userForAccessToken(tokens.accessToken, resource, now).shouldNotBeNull()
    }
}
