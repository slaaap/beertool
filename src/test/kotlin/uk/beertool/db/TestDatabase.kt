package uk.beertool.db

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

object TestDatabase {
    private val ready: Boolean by lazy {
        val pg = PostgresTestContainer.instance
        val ds = DatabaseFactory.hikari(pg.jdbcUrl, pg.username, pg.password)
        Flyway.configure().dataSource(ds).load().migrate()
        Database.connect(ds)
        true
    }

    fun ensureReady() = check(ready)
}
