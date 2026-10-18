package uk.beertool.telegram

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.auth.Secrets
import uk.beertool.db.forDb
import java.time.Duration
import java.time.Instant

object TelegramLinks : Table("telegram_links") {
    val telegramUserId = long("telegram_user_id")
    val userId = long("user_id")
    val chatId = long("chat_id")
    val currentBatchId = long("current_batch_id").nullable()
    val linkedAt = timestamp("linked_at")
    override val primaryKey = PrimaryKey(telegramUserId)
}

object TelegramLinkTokens : Table("telegram_link_tokens") {
    val tokenHash = text("token_hash")
    val userId = long("user_id")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

data class TelegramLink(
    val telegramUserId: Long,
    val userId: Long,
    val chatId: Long,
    val currentBatchId: Long?,
)

val LINK_TOKEN_TTL: Duration = Duration.ofMinutes(15)

object TelegramLinkRepository {

    fun issueToken(userId: Long, now: Instant = Instant.now()): String = transaction {
        val token = Secrets.newToken()
        TelegramLinkTokens.deleteWhere { (TelegramLinkTokens.userId eq userId) or (TelegramLinkTokens.expiresAt lessEq now) }
        TelegramLinkTokens.insert {
            it[tokenHash] = Secrets.sha256(token)
            it[TelegramLinkTokens.userId] = userId
            it[expiresAt] = now.plus(LINK_TOKEN_TTL).forDb()
        }
        token
    }

    fun redeem(token: String, telegramUserId: Long, chatId: Long, now: Instant = Instant.now()): TelegramLink? = transaction {
        val hash = Secrets.sha256(token)
        val userId = TelegramLinkTokens.selectAll()
            .where { (TelegramLinkTokens.tokenHash eq hash) and (TelegramLinkTokens.expiresAt greater now) }
            .singleOrNull()
            ?.get(TelegramLinkTokens.userId)
            ?: return@transaction null
        TelegramLinkTokens.deleteWhere { TelegramLinkTokens.tokenHash eq hash }
        TelegramLinks.deleteWhere { (TelegramLinks.userId eq userId) or (TelegramLinks.telegramUserId eq telegramUserId) }
        TelegramLinks.insert {
            it[TelegramLinks.telegramUserId] = telegramUserId
            it[TelegramLinks.userId] = userId
            it[TelegramLinks.chatId] = chatId
            it[linkedAt] = now.forDb()
        }
        TelegramLink(telegramUserId, userId, chatId, currentBatchId = null)
    }

    fun findByTelegramUser(telegramUserId: Long): TelegramLink? = transaction {
        TelegramLinks.selectAll().where { TelegramLinks.telegramUserId eq telegramUserId }.singleOrNull()?.toLink()
    }

    fun findByUser(userId: Long): TelegramLink? = transaction {
        TelegramLinks.selectAll().where { TelegramLinks.userId eq userId }.singleOrNull()?.toLink()
    }

    // Running boil timers hold the chat id, so they go too, or the old chat would keep hearing about the brew.
    fun unlinkUser(userId: Long): Boolean = transaction {
        BoilTimers.deleteWhere { BoilTimers.userId eq userId }
        TelegramLinks.deleteWhere { TelegramLinks.userId eq userId } > 0
    }

    fun setCurrentBatch(telegramUserId: Long, batchId: Long?) = transaction {
        TelegramLinks.update({ TelegramLinks.telegramUserId eq telegramUserId }) { it[currentBatchId] = batchId }
    }

    private fun ResultRow.toLink() = TelegramLink(
        telegramUserId = this[TelegramLinks.telegramUserId],
        userId = this[TelegramLinks.userId],
        chatId = this[TelegramLinks.chatId],
        currentBatchId = this[TelegramLinks.currentBatchId],
    )
}
