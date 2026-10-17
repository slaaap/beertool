package uk.beertool.oauth

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.auth.Secrets
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

object OAuthGrants : LongIdTable("oauth_grants") {
    val userId = long("user_id")
    val clientId = text("client_id")
    val clientName = text("client_name")
    val scope = text("scope")
    val resource = text("resource")
    val refreshHash = text("refresh_hash").nullable()
    val refreshExpiresAt = timestamp("refresh_expires_at").nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
}

object OAuthCodes : Table("oauth_codes") {
    val codeHash = text("code_hash")
    val userId = long("user_id")
    val clientId = text("client_id")
    val clientName = text("client_name")
    val redirectUri = text("redirect_uri")
    val codeChallenge = text("code_challenge")
    val scope = text("scope")
    val resource = text("resource")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(codeHash)
}

object OAuthAccessTokens : Table("oauth_access_tokens") {
    val tokenHash = text("token_hash")
    val grantId = long("grant_id")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

val CODE_TTL: Duration = Duration.ofMinutes(5)
val ACCESS_TTL: Duration = Duration.ofHours(1)
val REFRESH_TTL: Duration = Duration.ofDays(30)

data class AuthorizationRequest(
    val client: OAuthClient,
    val redirectUri: String,
    val codeChallenge: String,
    val scope: String,
    val resource: String,
)

data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long, val scope: String)

data class OAuthGrant(
    val id: Long,
    val clientName: String,
    val clientId: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

class OAuthException(val error: String, description: String) : RuntimeException(description)

fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

private val VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")

object OAuthStore {

    fun issueCode(userId: Long, request: AuthorizationRequest, now: Instant = Instant.now()): String = transaction {
        OAuthCodes.deleteWhere { OAuthCodes.expiresAt lessEq now }
        val code = "btc_" + Secrets.newToken()
        OAuthCodes.insert {
            it[codeHash] = Secrets.sha256(code)
            it[OAuthCodes.userId] = userId
            it[clientId] = request.client.clientId
            it[clientName] = request.client.name
            it[redirectUri] = request.redirectUri
            it[codeChallenge] = request.codeChallenge
            it[scope] = request.scope
            it[resource] = request.resource
            it[expiresAt] = now.plus(CODE_TTL).truncatedTo(ChronoUnit.MICROS)
        }
        code
    }

    // Consumed in its own transaction, by whoever deletes the row, so neither a rollback nor a racing request can reuse it.
    fun redeemCode(code: String, clientId: String, redirectUri: String, verifier: String, resource: String?, now: Instant = Instant.now()): Tokens {
        val hash = Secrets.sha256(code)
        val row = transaction {
            OAuthCodes.selectAll().where { OAuthCodes.codeHash eq hash }.singleOrNull()
                ?.takeIf { OAuthCodes.deleteWhere { OAuthCodes.codeHash eq hash } == 1 }
        } ?: throw OAuthException("invalid_grant", "The authorization code is unknown or already used.")
        when {
            !row[OAuthCodes.expiresAt].isAfter(now) -> throw OAuthException("invalid_grant", "The authorization code has expired.")
            row[OAuthCodes.clientId] != clientId -> throw OAuthException("invalid_grant", "The code was issued to another client.")
            row[OAuthCodes.redirectUri] != redirectUri -> throw OAuthException("invalid_grant", "redirect_uri does not match the authorization request.")
            !VERIFIER.matches(verifier) || pkceChallenge(verifier) != row[OAuthCodes.codeChallenge] ->
                throw OAuthException("invalid_grant", "code_verifier does not match the code challenge.")
            resource != null && resource != row[OAuthCodes.resource] -> throw OAuthException("invalid_target", "resource does not match the authorization request.")
        }
        return transaction {
            val userId = row[OAuthCodes.userId]
            val refresh = newRefresh()
            val grantId = OAuthGrants.insertAndGetId {
                it[OAuthGrants.userId] = userId
                it[OAuthGrants.clientId] = clientId
                it[clientName] = row[OAuthCodes.clientName]
                it[scope] = row[OAuthCodes.scope]
                it[OAuthGrants.resource] = row[OAuthCodes.resource]
                it[refreshHash] = Secrets.sha256(refresh)
                it[refreshExpiresAt] = now.plus(REFRESH_TTL).truncatedTo(ChronoUnit.MICROS)
                it[createdAt] = now.truncatedTo(ChronoUnit.MICROS)
            }.value
            Tokens(issueAccess(grantId, now), refresh, ACCESS_TTL.seconds, row[OAuthCodes.scope])
        }
    }

    // Refresh tokens rotate: each works once, and only the request that swaps the stored hash gets the replacement.
    fun refresh(refreshToken: String, clientId: String, now: Instant = Instant.now()): Tokens = transaction {
        val presented = Secrets.sha256(refreshToken)
        val row = OAuthGrants.selectAll().where { OAuthGrants.refreshHash eq presented }.singleOrNull()
        if (row == null || row[OAuthGrants.clientId] != clientId || row[OAuthGrants.refreshExpiresAt]?.isAfter(now) != true) {
            throw OAuthException("invalid_grant", "The refresh token is invalid or expired.")
        }
        val grantId = row[OAuthGrants.id].value
        val refresh = newRefresh()
        val swapped = OAuthGrants.update({ (OAuthGrants.id eq grantId) and (OAuthGrants.refreshHash eq presented) }) {
            it[refreshHash] = Secrets.sha256(refresh)
            it[refreshExpiresAt] = now.plus(REFRESH_TTL).truncatedTo(ChronoUnit.MICROS)
            it[lastUsedAt] = now.truncatedTo(ChronoUnit.MICROS)
        }
        if (swapped != 1) throw OAuthException("invalid_grant", "The refresh token was already used.")
        Tokens(issueAccess(grantId, now), refresh, ACCESS_TTL.seconds, row[OAuthGrants.scope])
    }

    fun userForAccessToken(token: String, resource: String, now: Instant = Instant.now()): User? = transaction {
        val access = OAuthAccessTokens.selectAll().where { OAuthAccessTokens.tokenHash eq Secrets.sha256(token) }.singleOrNull()
            ?.takeIf { it[OAuthAccessTokens.expiresAt].isAfter(now) } ?: return@transaction null
        val grant = OAuthGrants.selectAll().where { OAuthGrants.id eq access[OAuthAccessTokens.grantId] }.singleOrNull()
            ?.takeIf { it[OAuthGrants.resource] == resource } ?: return@transaction null
        OAuthGrants.update({ OAuthGrants.id eq grant[OAuthGrants.id] }) { it[lastUsedAt] = now.truncatedTo(ChronoUnit.MICROS) }
        UserRepository.findById(grant[OAuthGrants.userId])
    }

    fun listGrants(userId: Long): List<OAuthGrant> = transaction {
        OAuthGrants.selectAll()
            .where { OAuthGrants.userId eq userId }
            .orderBy(OAuthGrants.createdAt to SortOrder.DESC)
            .map { it.toGrant() }
    }

    fun revokeGrant(id: Long, userId: Long): Boolean = transaction {
        OAuthGrants.deleteWhere { (OAuthGrants.id eq id) and (OAuthGrants.userId eq userId) } > 0
    }

    private fun newRefresh() = "btr_" + Secrets.newToken()

    private fun issueAccess(grantId: Long, now: Instant): String {
        OAuthAccessTokens.deleteWhere { OAuthAccessTokens.expiresAt lessEq now }
        val token = "bta_" + Secrets.newToken()
        OAuthAccessTokens.insert {
            it[tokenHash] = Secrets.sha256(token)
            it[OAuthAccessTokens.grantId] = grantId
            it[expiresAt] = now.plus(ACCESS_TTL).truncatedTo(ChronoUnit.MICROS)
        }
        return token
    }

    private fun ResultRow.toGrant() = OAuthGrant(
        id = this[OAuthGrants.id].value,
        clientName = this[OAuthGrants.clientName],
        clientId = this[OAuthGrants.clientId],
        createdAt = this[OAuthGrants.createdAt],
        lastUsedAt = this[OAuthGrants.lastUsedAt],
    )
}
