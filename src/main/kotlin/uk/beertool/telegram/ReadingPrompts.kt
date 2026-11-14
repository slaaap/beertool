package uk.beertool.telegram

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.beertool.db.forDb
import java.time.Instant

object ReadingPrompts : Table("telegram_reading_prompts") {
    val telegramUserId = long("telegram_user_id")
    val messageId = long("message_id")
    val batchId = long("batch_id")
    val reading = text("reading")
    val askedAt = timestamp("asked_at")
    override val primaryKey = PrimaryKey(telegramUserId, messageId)
}

data class PendingReading(val messageId: Long, val batchId: Long, val key: String)

// Each prompt remembers the brew it was asked about, so with two brews on the go an answer lands on the right one.
object ReadingPromptRepository {

    fun ask(telegramUserId: Long, messageId: Long, batchId: Long, key: String, now: Instant = Instant.now()) = transaction {
        ReadingPrompts.deleteWhere {
            (ReadingPrompts.telegramUserId eq telegramUserId) and (ReadingPrompts.batchId eq batchId) and (reading eq key)
        }
        ReadingPrompts.insert {
            it[ReadingPrompts.telegramUserId] = telegramUserId
            it[ReadingPrompts.messageId] = messageId
            it[ReadingPrompts.batchId] = batchId
            it[reading] = key
            it[askedAt] = now.forDb()
        }
    }

    // A reply names its prompt; a message that isn't a reply answers the question asked last.
    fun pending(telegramUserId: Long, replyTo: Long?): PendingReading? = transaction {
        val mine = ReadingPrompts.selectAll().where { ReadingPrompts.telegramUserId eq telegramUserId }
        val found = if (replyTo != null) mine.andWhere { ReadingPrompts.messageId eq replyTo }
        else mine.orderBy(ReadingPrompts.askedAt, SortOrder.DESC).limit(1)
        found.firstOrNull()?.toPending()
    }

    fun answered(telegramUserId: Long, messageId: Long) = transaction {
        ReadingPrompts.deleteWhere { (ReadingPrompts.telegramUserId eq telegramUserId) and (ReadingPrompts.messageId eq messageId) }
    }

    fun clear(telegramUserId: Long) = transaction {
        ReadingPrompts.deleteWhere { ReadingPrompts.telegramUserId eq telegramUserId }
    }

    private fun ResultRow.toPending() = PendingReading(this[ReadingPrompts.messageId], this[ReadingPrompts.batchId], this[ReadingPrompts.reading])
}
