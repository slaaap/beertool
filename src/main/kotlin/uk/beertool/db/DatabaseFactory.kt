package uk.beertool.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(): HikariDataSource {
        val url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/beertool"
        log.info("Connecting to Postgres at {}", url)
        val ds = hikari(
            url = url,
            user = System.getenv("DATABASE_USER") ?: "beertool",
            password = System.getenv("DATABASE_PASSWORD") ?: "beertool",
        )
        Flyway.configure().dataSource(ds).load().migrate()
        Database.connect(ds)
        return ds
    }

    fun hikari(url: String, user: String, password: String) =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }.let(::HikariDataSource)
}
