package uk.beertool.mcp

import io.kotest.inspectors.forNone
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.user.UserRepository
import java.time.Instant
import java.util.UUID

class ApiTokenRepositoryTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should resolve a token to its owner and note when it was used`() {
        val userId = newUser()
        val token = ApiTokenRepository.create(userId, "Claude Code")
        val usedAt = Instant.parse("2026-10-16T12:00:00Z")

        val user = ApiTokenRepository.userFor(token, usedAt)

        user.shouldNotBeNull().id shouldBe userId
        ApiTokenRepository.listByUser(userId).single().lastUsedAt shouldBe usedAt
    }

    @Test
    fun `should keep only a hash of the token`() {
        val userId = newUser()

        val token = ApiTokenRepository.create(userId, "Claude Code")

        token shouldStartWith "bt_"
        transaction { ApiTokens.selectAll().map { it[ApiTokens.tokenHash] } }.forNone { it shouldContain token.removePrefix("bt_") }
    }

    @Test
    fun `should stop accepting a token once it is revoked`() {
        val userId = newUser()
        val token = ApiTokenRepository.create(userId, "Claude Code")
        val id = ApiTokenRepository.listByUser(userId).single().id

        ApiTokenRepository.revoke(id, userId)

        ApiTokenRepository.userFor(token).shouldBeNull()
        ApiTokenRepository.listByUser(userId).shouldBeEmpty()
    }

    @Test
    fun `should not let one brewer revoke another's token`() {
        val owner = newUser()
        val token = ApiTokenRepository.create(owner, "Claude Code")
        val id = ApiTokenRepository.listByUser(owner).single().id

        val revoked = ApiTokenRepository.revoke(id, newUser())

        revoked shouldBe false
        ApiTokenRepository.userFor(token).shouldNotBeNull()
    }

    private fun newUser() = UserRepository.create("tok-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id
}
