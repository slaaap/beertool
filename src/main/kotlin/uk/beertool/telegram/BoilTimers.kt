package uk.beertool.telegram

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.recipe.BoilAddition
import java.time.Instant
import java.time.temporal.ChronoUnit

object BoilTimers : Table("telegram_boil_timers") {
    val batchId = long("batch_id")
    val userId = long("user_id")
    val chatId = long("chat_id")
    val endsAt = timestamp("ends_at")
    val announcedDownTo = integer("announced_down_to")
    override val primaryKey = PrimaryKey(batchId)
}

// announcedDownTo is the smallest "minutes before flame-out" already announced, so each addition goes out once.
data class BoilTimer(
    val batchId: Long,
    val userId: Long,
    val chatId: Long,
    val endsAt: Instant,
    val announcedDownTo: Int,
)

data class BoilAlert(
    val additions: List<BoilAddition>,
    val flameOut: Boolean,
    val announcedDownTo: Int,
)

fun BoilTimer.alertAt(now: Instant, additions: List<BoilAddition>): BoilAlert? {
    val due = additions.filter {
        it.minutesBeforeEnd < announcedDownTo && !now.isBefore(endsAt.minus(it.minutesBeforeEnd.toLong(), ChronoUnit.MINUTES))
    }
    val flameOut = !now.isBefore(endsAt)
    if (due.isEmpty() && !flameOut) return null
    return BoilAlert(due, flameOut, due.minOfOrNull { it.minutesBeforeEnd } ?: announcedDownTo)
}

fun BoilTimer.minutesLeft(now: Instant) = ChronoUnit.MINUTES.between(now, endsAt.plusSeconds(59)).coerceAtLeast(0)

object BoilTimerRepository {

    fun start(batchId: Long, userId: Long, chatId: Long, boilMinutes: Int, now: Instant = Instant.now()): BoilTimer = transaction {
        val timer = BoilTimer(
            batchId = batchId,
            userId = userId,
            chatId = chatId,
            endsAt = now.plus(boilMinutes.toLong(), ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS),
            announcedDownTo = boilMinutes + 1,
        )
        BoilTimers.deleteWhere { BoilTimers.batchId eq batchId }
        BoilTimers.insert {
            it[BoilTimers.batchId] = timer.batchId
            it[BoilTimers.userId] = timer.userId
            it[BoilTimers.chatId] = timer.chatId
            it[endsAt] = timer.endsAt
            it[announcedDownTo] = timer.announcedDownTo
        }
        timer
    }

    fun find(batchId: Long): BoilTimer? = transaction {
        BoilTimers.selectAll().where { BoilTimers.batchId eq batchId }.singleOrNull()?.toTimer()
    }

    fun all(): List<BoilTimer> = transaction { BoilTimers.selectAll().map { it.toTimer() } }

    // Only the caller that moves the mark from where it read it may send, so two ticks never announce the same addition.
    fun claim(batchId: Long, from: Int, to: Int): Boolean = transaction {
        BoilTimers.update({ (BoilTimers.batchId eq batchId) and (BoilTimers.announcedDownTo eq from) }) {
            it[announcedDownTo] = to
        } == 1
    }

    fun stop(batchId: Long): Boolean = transaction {
        BoilTimers.deleteWhere { BoilTimers.batchId eq batchId } > 0
    }

    private fun ResultRow.toTimer() = BoilTimer(
        batchId = this[BoilTimers.batchId],
        userId = this[BoilTimers.userId],
        chatId = this[BoilTimers.chatId],
        endsAt = this[BoilTimers.endsAt],
        announcedDownTo = this[BoilTimers.announcedDownTo],
    )
}
