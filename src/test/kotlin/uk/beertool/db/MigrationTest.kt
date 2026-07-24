package uk.beertool.db

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class MigrationTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should apply the migrations and leave the schema valid`() {
        val flyway = flyway()

        flyway.info().applied().map { it.version.version } shouldContain "1"
        flyway.validateWithResult().validationSuccessful shouldBe true
    }

    @Test
    fun `should create the core tables`() {
        tableNames() shouldContainAll listOf(
            "users", "recipes", "recipe_fermentables", "recipe_hops", "recipe_yeasts", "recipe_extras", "batches",
        )
    }

    private fun flyway(): Flyway = PostgresTestContainer.instance.let {
        Flyway.configure().dataSource(it.jdbcUrl, it.username, it.password).load()
    }

    private fun tableNames(): List<String> {
        val pg = PostgresTestContainer.instance
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement()
                .executeQuery("select table_name from information_schema.tables where table_schema = 'public'")
                .use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
        }
    }
}
