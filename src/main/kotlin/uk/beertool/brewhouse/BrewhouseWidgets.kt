package uk.beertool.brewhouse

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import uk.beertool.db.forDb
import java.time.Instant

object BrewhouseWidgets : Table("brewhouse_widgets") {
    val userId = long("user_id")
    val html = text("html")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

data class BrewhouseWidget(val html: String, val updatedAt: Instant)

// Kept in the database, as the controller only uploads it when it starts or its widget changes, not after a beertool restart.
object BrewhouseWidgetRepository {

    fun put(userId: Long, html: String, now: Instant = Instant.now()): Instant = transaction {
        val at = now.forDb()
        BrewhouseWidgets.upsert {
            it[BrewhouseWidgets.userId] = userId
            it[BrewhouseWidgets.html] = html
            it[updatedAt] = at
        }
        at
    }

    fun find(userId: Long): BrewhouseWidget? = transaction {
        BrewhouseWidgets.selectAll().where { BrewhouseWidgets.userId eq userId }.singleOrNull()
            ?.let { BrewhouseWidget(it[BrewhouseWidgets.html], it[BrewhouseWidgets.updatedAt]) }
    }

    fun updatedAt(userId: Long): Instant? = transaction {
        BrewhouseWidgets.select(BrewhouseWidgets.updatedAt).where { BrewhouseWidgets.userId eq userId }.singleOrNull()
            ?.get(BrewhouseWidgets.updatedAt)
    }

    fun delete(userId: Long): Boolean = transaction { BrewhouseWidgets.deleteWhere { BrewhouseWidgets.userId eq userId } > 0 }
}
