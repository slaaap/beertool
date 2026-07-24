package uk.beertool.user

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import java.time.Instant

internal val PrefsJson = Json {

    ignoreUnknownKeys = true

    encodeDefaults = true
}

object Users : LongIdTable("users") {
    val email = text("email")
    val passwordHash = text("password_hash")
    val displayName = text("display_name")
    val createdAt = timestamp("created_at")
    val preferences = jsonb("preferences", PrefsJson, BrewerPreferences.serializer())
}

data class User(
    val id: Long,
    val email: String,
    val displayName: String,
    val createdAt: Instant,
    val preferences: BrewerPreferences = BrewerPreferences(),
    val canWrite: Boolean = true,
)

fun User.readOnly() = copy(canWrite = false)

data class UserWithHash(
    val user: User,
    val passwordHash: String,
)
