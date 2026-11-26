package uk.beertool.brewhouse

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.auth.Secrets
import uk.beertool.db.forDb
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.time.Instant

object BrewhouseKeys : Table("brewhouse_keys") {
    val userId = long("user_id")
    val keyHash = text("key_hash")
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(userId)
}

data class BrewhouseKey(val createdAt: Instant, val lastUsedAt: Instant?)

// Its own prefix, so a leaked key is told apart from a personal token at a glance.
private const val KEY_PREFIX = "bh_"

// A key opens the Brewhouse API and nothing else, so a controller in a shed never holds access to the recipes.
object BrewhouseKeyRepository {

    fun issue(userId: Long, now: Instant = Instant.now()): String = transaction {
        val key = KEY_PREFIX + Secrets.newToken()
        BrewhouseKeys.deleteWhere { BrewhouseKeys.userId eq userId }
        BrewhouseKeys.insert {
            it[BrewhouseKeys.userId] = userId
            it[keyHash] = Secrets.sha256(key)
            it[createdAt] = now.forDb()
        }
        key
    }

    fun userFor(key: String, now: Instant = Instant.now()): User? = transaction {
        val row = BrewhouseKeys.selectAll().where { BrewhouseKeys.keyHash eq Secrets.sha256(key) }.singleOrNull() ?: return@transaction null
        BrewhouseKeys.update({ BrewhouseKeys.userId eq row[BrewhouseKeys.userId] }) { it[lastUsedAt] = now.forDb() }
        UserRepository.findById(row[BrewhouseKeys.userId])
    }

    fun find(userId: Long): BrewhouseKey? = transaction {
        BrewhouseKeys.selectAll().where { BrewhouseKeys.userId eq userId }.singleOrNull()
            ?.let { BrewhouseKey(it[BrewhouseKeys.createdAt], it[BrewhouseKeys.lastUsedAt]) }
    }

    fun revoke(userId: Long): Boolean = transaction { BrewhouseKeys.deleteWhere { BrewhouseKeys.userId eq userId } > 0 }
}
