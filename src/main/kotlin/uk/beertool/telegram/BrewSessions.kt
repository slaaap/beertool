package uk.beertool.telegram

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.batch.BrewDayStage
import uk.beertool.db.forDb
import uk.beertool.recipe.BoilAddition
import java.time.Instant
import java.time.temporal.ChronoUnit

object BrewSessions : Table("telegram_brew_sessions") {
    val batchId = long("batch_id")
    val userId = long("user_id")
    val chatId = long("chat_id")
    val panelMessageId = long("panel_message_id").nullable()
    val stage = text("stage")
    val mashStep = integer("mash_step")
    val timer = text("timer").nullable()
    val timerEndsAt = timestamp("timer_ends_at").nullable()
    val announcedDownTo = integer("announced_down_to").nullable()
    val panelRefreshedAt = timestamp("panel_refreshed_at").nullable()
    override val primaryKey = PrimaryKey(batchId)
}

enum class TimerKind { MASH, BOIL }

// For the boil, announcedDownTo is the smallest "minutes before flame-out" already announced, so each addition goes out once.
data class BrewTimer(val kind: TimerKind, val endsAt: Instant, val announcedDownTo: Int = 0)

// mashStep is the step running or up next; once it reaches the step count, the mash is done.
data class BrewSession(
    val batchId: Long,
    val userId: Long,
    val chatId: Long,
    val panelMessageId: Long?,
    val stage: BrewDayStage,
    val mashStep: Int,
    val timer: BrewTimer?,
    val panelRefreshedAt: Instant?,
)

data class BoilAlert(
    val additions: List<BoilAddition>,
    val flameOut: Boolean,
    val announcedDownTo: Int,
)

fun BrewTimer.isDue(now: Instant) = !now.isBefore(endsAt)

fun BrewTimer.boilAlertAt(now: Instant, additions: List<BoilAddition>): BoilAlert? {
    val due = additions.filter {
        it.minutesBeforeEnd < announcedDownTo && !now.isBefore(endsAt.minus(it.minutesBeforeEnd.toLong(), ChronoUnit.MINUTES))
    }
    val flameOut = isDue(now)
    if (due.isEmpty() && !flameOut) return null
    return BoilAlert(due, flameOut, due.minOfOrNull { it.minutesBeforeEnd } ?: announcedDownTo)
}

fun BrewTimer.minutesLeft(now: Instant) = ChronoUnit.MINUTES.between(now, endsAt.plusSeconds(59)).coerceAtLeast(0)

object BrewSessionRepository {

    // A brew can be picked up again from another chat after relinking, so the chat follows whoever opened it last.
    fun open(batchId: Long, userId: Long, chatId: Long, stage: BrewDayStage): BrewSession = transaction {
        BrewSessions.insertIgnore {
            it[BrewSessions.batchId] = batchId
            it[BrewSessions.userId] = userId
            it[BrewSessions.chatId] = chatId
            it[BrewSessions.stage] = stage.name
            it[mashStep] = 0
        }
        BrewSessions.update({ BrewSessions.batchId eq batchId }) { it[BrewSessions.chatId] = chatId }
        find(batchId)!!
    }

    fun find(batchId: Long): BrewSession? = transaction {
        BrewSessions.selectAll().where { BrewSessions.batchId eq batchId }.singleOrNull()?.toSession()
    }

    fun withTimers(): List<BrewSession> = transaction {
        BrewSessions.selectAll().where { BrewSessions.timer.isNotNull() }.map { it.toSession() }
    }

    fun moveTo(batchId: Long, stage: BrewDayStage) = transaction {
        BrewSessions.update({ BrewSessions.batchId eq batchId }) { it[BrewSessions.stage] = stage.name }
    }

    fun setPanel(batchId: Long, messageId: Long?, now: Instant) = transaction {
        BrewSessions.update({ BrewSessions.batchId eq batchId }) {
            it[panelMessageId] = messageId
            it[panelRefreshedAt] = now.forDb()
        }
    }

    // Only the panel that failed is let go, so one posted meanwhile by another tick is kept.
    fun dropPanel(batchId: Long, messageId: Long) = transaction {
        BrewSessions.update({ (BrewSessions.batchId eq batchId) and (BrewSessions.panelMessageId eq messageId) }) { it[panelMessageId] = null }
    }

    fun panelRefreshed(batchId: Long, now: Instant) = transaction {
        BrewSessions.update({ BrewSessions.batchId eq batchId }) { it[panelRefreshedAt] = now.forDb() }
    }

    fun startMashStep(batchId: Long, step: Int, minutes: Int, now: Instant) = transaction {
        BrewSessions.update({ BrewSessions.batchId eq batchId }) {
            it[stage] = BrewDayStage.MASH.name
            it[mashStep] = step
            it[timer] = TimerKind.MASH.name
            it[timerEndsAt] = now.plus(minutes.toLong(), ChronoUnit.MINUTES).forDb()
            it[announcedDownTo] = null
        }
    }

    fun startBoil(batchId: Long, boilMinutes: Int, now: Instant) = transaction {
        BrewSessions.update({ BrewSessions.batchId eq batchId }) {
            it[stage] = BrewDayStage.BOIL.name
            it[timer] = TimerKind.BOIL.name
            it[timerEndsAt] = now.plus(boilMinutes.toLong(), ChronoUnit.MINUTES).forDb()
            it[announcedDownTo] = boilMinutes + 1
        }
    }

    // The finishers and claim only succeed for the caller that moves the timer on from the state it read,
    // so a tick and a button press never both announce the same moment.
    fun finishMashStep(batchId: Long, endsAt: Instant, nextStep: Int): Boolean = transaction {
        BrewSessions.update({
            (BrewSessions.batchId eq batchId) and (BrewSessions.timer eq TimerKind.MASH.name) and (BrewSessions.timerEndsAt eq endsAt)
        }) {
            it[mashStep] = nextStep
            it[timer] = null
            it[timerEndsAt] = null
        } == 1
    }

    fun claimBoil(batchId: Long, from: Int, to: Int): Boolean = transaction {
        BrewSessions.update({
            (BrewSessions.batchId eq batchId) and (BrewSessions.timer eq TimerKind.BOIL.name) and (BrewSessions.announcedDownTo eq from)
        }) {
            it[announcedDownTo] = to
        } == 1
    }

    fun finishBoil(batchId: Long): Boolean = transaction {
        BrewSessions.update({ (BrewSessions.batchId eq batchId) and (BrewSessions.timer eq TimerKind.BOIL.name) }) {
            it[stage] = BrewDayStage.CHILL.name
            it[timer] = null
            it[timerEndsAt] = null
            it[announcedDownTo] = null
        } == 1
    }

    fun stopTimer(batchId: Long): Boolean = transaction {
        BrewSessions.update({ (BrewSessions.batchId eq batchId) and BrewSessions.timer.isNotNull() }) {
            it[timer] = null
            it[timerEndsAt] = null
            it[announcedDownTo] = null
        } > 0
    }

    fun close(batchId: Long): Boolean = transaction {
        BrewSessions.deleteWhere { BrewSessions.batchId eq batchId } > 0
    }

    private fun ResultRow.toSession() = BrewSession(
        batchId = this[BrewSessions.batchId],
        userId = this[BrewSessions.userId],
        chatId = this[BrewSessions.chatId],
        panelMessageId = this[BrewSessions.panelMessageId],
        stage = BrewDayStage.valueOf(this[BrewSessions.stage]),
        mashStep = this[BrewSessions.mashStep],
        timer = this[BrewSessions.timer]?.let { kind ->
            BrewTimer(TimerKind.valueOf(kind), this[BrewSessions.timerEndsAt]!!, this[BrewSessions.announcedDownTo] ?: 0)
        },
        panelRefreshedAt = this[BrewSessions.panelRefreshedAt],
    )
}
