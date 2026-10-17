package uk.beertool.telegram

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.UserRepository
import java.time.Instant
import java.util.UUID

class BoilTimerRepositoryTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should let only one of two ticks claim the same announcement`() {
        val userId = UserRepository.create("boil-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id
        val recipeId = RecipeRepository.create(userId, NewRecipe(name = "Pale")).id
        val batchId = BatchRepository.create(userId, NewBatch(recipeId = recipeId))!!.id
        val timer = BoilTimerRepository.start(batchId, userId, chatId = 1, boilMinutes = 60, now = Instant.parse("2026-10-17T10:00:00Z"))

        val first = BoilTimerRepository.claim(batchId, from = timer.announcedDownTo, to = 60)
        val second = BoilTimerRepository.claim(batchId, from = timer.announcedDownTo, to = 60)

        first.shouldBeTrue()
        second.shouldBeFalse()
        BoilTimerRepository.find(batchId)!!.announcedDownTo shouldBe 60
    }
}
