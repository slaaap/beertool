package uk.beertool.push

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import uk.beertool.db.forDb
import java.time.Instant

object PushSubscriptions : Table("push_subscriptions") {
    val endpoint = text("endpoint")
    val userId = long("user_id")
    val p256dh = text("p256dh")
    val auth = text("auth")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(endpoint)
}

data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String)

object PushSubscriptionRepository {

    // An endpoint belongs to one browser, so subscribing it again, even as another brewer, moves it rather than duplicating it.
    fun save(userId: Long, subscription: PushSubscription, now: Instant = Instant.now()) = transaction {
        PushSubscriptions.upsert {
            it[endpoint] = subscription.endpoint
            it[PushSubscriptions.userId] = userId
            it[p256dh] = subscription.p256dh
            it[auth] = subscription.auth
            it[createdAt] = now.forDb()
        }
        Unit
    }

    fun forUser(userId: Long): List<PushSubscription> = transaction {
        PushSubscriptions.selectAll().where { PushSubscriptions.userId eq userId }
            .map { PushSubscription(it[PushSubscriptions.endpoint], it[PushSubscriptions.p256dh], it[PushSubscriptions.auth]) }
    }

    fun delete(endpoint: String) = transaction { PushSubscriptions.deleteWhere { PushSubscriptions.endpoint eq endpoint } > 0 }

    fun delete(userId: Long, endpoint: String) = transaction {
        PushSubscriptions.deleteWhere { (PushSubscriptions.endpoint eq endpoint) and (PushSubscriptions.userId eq userId) } > 0
    }
}
