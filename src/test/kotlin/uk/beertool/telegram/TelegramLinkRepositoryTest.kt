package uk.beertool.telegram

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.user.UserRepository
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class TelegramLinkRepositoryTest {

    private val telegramId = Random.nextLong(1, Long.MAX_VALUE)

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should issue tokens Telegram accepts as a start parameter`() {
        val userId = newUser()

        val token = TelegramLinkRepository.issueToken(userId)

        token shouldMatch Regex("[A-Za-z0-9_-]{1,64}")
    }

    @Test
    fun `should refuse a token once it has expired`() {
        val issuedAt = Instant.parse("2026-10-16T10:00:00Z")
        val token = TelegramLinkRepository.issueToken(newUser(), issuedAt)

        val link = TelegramLinkRepository.redeem(token, telegramId, telegramId, issuedAt.plus(LINK_TOKEN_TTL).plusSeconds(1))

        link.shouldBeNull()
    }

    @Test
    fun `should replace the earlier token when a new one is issued`() {
        val userId = newUser()
        val first = TelegramLinkRepository.issueToken(userId)

        TelegramLinkRepository.issueToken(userId)

        TelegramLinkRepository.redeem(first, telegramId, telegramId).shouldBeNull()
    }

    @Test
    fun `should move a beertool account to the Telegram account that links it last`() {
        val userId = newUser()
        TelegramLinkRepository.redeem(TelegramLinkRepository.issueToken(userId), telegramId, telegramId)
        val newTelegramId = telegramId - 1

        TelegramLinkRepository.redeem(TelegramLinkRepository.issueToken(userId), newTelegramId, newTelegramId)

        TelegramLinkRepository.findByTelegramUser(telegramId).shouldBeNull()
        TelegramLinkRepository.findByUser(userId).shouldNotBeNull().telegramUserId shouldBe newTelegramId
    }

    private fun newUser() = UserRepository.create("u-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id
}
