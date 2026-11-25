package uk.beertool.brewhouse

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.beertool.db.forDb
import java.time.Instant

object BrewhouseReadings : LongIdTable("brewhouse_readings") {
    val batchId = long("batch_id")
    val at = timestamp("at")
    val phase = text("phase")
    val tempC = double("temp_c").nullable()
    val targetC = double("target_c").nullable()
    val powerW = integer("power_w").nullable()
}

object BrewhouseReadingRepository {

    fun add(batchId: Long, reading: Reading): Unit = transaction {
        BrewhouseReadings.insert {
            it[BrewhouseReadings.batchId] = batchId
            it[at] = reading.at.forDb()
            it[phase] = reading.phase.name
            it[tempC] = reading.tempC
            it[targetC] = reading.targetC
            it[powerW] = reading.powerW
        }
    }

    fun forBatch(batchId: Long, since: Instant? = null): List<Reading> = transaction {
        BrewhouseReadings.selectAll()
            .where { (BrewhouseReadings.batchId eq batchId).let { b -> since?.let { b and (BrewhouseReadings.at greater it) } ?: b } }
            .orderBy(BrewhouseReadings.at to SortOrder.ASC, BrewhouseReadings.id to SortOrder.ASC)
            .map {
                Reading(
                    at = it[BrewhouseReadings.at],
                    phase = Phase.valueOf(it[BrewhouseReadings.phase]),
                    tempC = it[BrewhouseReadings.tempC],
                    targetC = it[BrewhouseReadings.targetC],
                    powerW = it[BrewhouseReadings.powerW],
                )
            }
    }
}
