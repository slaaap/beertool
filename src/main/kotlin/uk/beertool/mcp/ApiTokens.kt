package uk.beertool.mcp

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.auth.Secrets
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

object ApiTokens : LongIdTable("api_tokens") {
    val userId = long("user_id")
    val name = text("name")
    val tokenHash = text("token_hash")
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
}

data class ApiToken(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

// The prefix makes a leaked token recognisable in a paste or a log.
private const val TOKEN_PREFIX = "bt_"

object ApiTokenRepository {

    fun create(userId: Long, name: String, now: Instant = Instant.now()): String = transaction {
        val token = TOKEN_PREFIX + Secrets.newToken()
        ApiTokens.insert {
            it[ApiTokens.userId] = userId
            it[ApiTokens.name] = name
            it[tokenHash] = Secrets.sha256(token)
            it[createdAt] = now.truncatedTo(ChronoUnit.MICROS)
        }
        token
    }

    fun userFor(token: String, now: Instant = Instant.now()): User? = transaction {
        val row = ApiTokens.selectAll().where { ApiTokens.tokenHash eq Secrets.sha256(token) }.singleOrNull() ?: return@transaction null
        ApiTokens.update({ ApiTokens.id eq row[ApiTokens.id] }) { it[lastUsedAt] = now.truncatedTo(ChronoUnit.MICROS) }
        UserRepository.findById(row[ApiTokens.userId])
    }

    fun listByUser(userId: Long): List<ApiToken> = transaction {
        ApiTokens.selectAll()
            .where { ApiTokens.userId eq userId }
            .orderBy(ApiTokens.createdAt to SortOrder.DESC)
            .map { it.toToken() }
    }

    fun revoke(id: Long, userId: Long): Boolean = transaction {
        ApiTokens.deleteWhere { (ApiTokens.id eq id) and (ApiTokens.userId eq userId) } > 0
    }

    private fun ResultRow.toToken() = ApiToken(
        id = this[ApiTokens.id].value,
        name = this[ApiTokens.name],
        createdAt = this[ApiTokens.createdAt],
        lastUsedAt = this[ApiTokens.lastUsedAt],
    )
}
