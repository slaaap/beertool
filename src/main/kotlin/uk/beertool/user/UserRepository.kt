package uk.beertool.user

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit

object UserRepository {

    fun create(email: String, passwordHash: String, displayName: String): User = transaction {

        val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val preferences = BrewerPreferences()
        val id = Users.insertAndGetId {
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.displayName] = displayName
            it[Users.createdAt] = createdAt
            it[Users.preferences] = preferences
        }.value
        User(id, email, displayName, createdAt, preferences)
    }

    fun updatePreferences(userId: Long, preferences: BrewerPreferences): User? = transaction {
        Users.update({ Users.id eq userId }) { it[Users.preferences] = preferences }
        findById(userId)
    }

    fun findByEmail(email: String): UserWithHash? = transaction {
        Users.selectAll().where { Users.email eq email }.singleOrNull()?.toUserWithHash()
    }

    fun findById(id: Long): User? = transaction {
        Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser()
    }

    private fun ResultRow.toUser() = User(
        id = this[Users.id].value,
        email = this[Users.email],
        displayName = this[Users.displayName],
        createdAt = this[Users.createdAt],
        preferences = this[Users.preferences],
    )

    private fun ResultRow.toUserWithHash() = UserWithHash(toUser(), this[Users.passwordHash])
}
