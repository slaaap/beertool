package uk.beertool.db

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.RecipeWater
import uk.beertool.user.UserRepository
import java.sql.DriverManager
import java.util.UUID

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

    @Test
    fun `should move lactic acid saved under its old name to the acid amount`() {
        val userId = UserRepository.create("m-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id
        val recipeId = RecipeRepository.create(userId, NewRecipe(name = "Old", water = RecipeWater(source = "Tap"))).id
        val batchId = BatchRepository.create(userId, NewBatch(recipeId = recipeId))!!.id
        sql("update recipes set water = '{\"source\": \"Tap\", \"lacticMl\": 4.5}' where id = $recipeId")
        sql("update batches set water = '{\"water\": {\"source\": \"Tap\", \"lacticMl\": 3.0}, \"sources\": []}' where id = $batchId")

        sql(javaClass.getResource("/db/migration/V7__acid_ml.sql")!!.readText())

        RecipeRepository.findById(recipeId, userId)!!.water!!.acidMl shouldBe 4.5
        BatchRepository.findById(batchId, userId)!!.water!!.water.acidMl shouldBe 3.0
    }

    private fun sql(statements: String) {
        val pg = PostgresTestContainer.instance
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { it.createStatement().execute(statements) }
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
