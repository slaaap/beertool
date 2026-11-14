package uk.beertool.telegram

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.BrewDayStage
import uk.beertool.batch.NewBatch
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.UserRepository
import java.time.Instant
import java.util.UUID

class BrewSessionRepositoryTest {

    private val now = Instant.parse("2026-10-17T10:00:00Z")

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should let only one of two ticks claim the same boil announcement`() {
        val (userId, batchId) = newBatch()
        BrewSessionRepository.open(batchId, userId, chatId = 1, stage = BrewDayStage.BOIL)
        BrewSessionRepository.startBoil(batchId, boilMinutes = 60, now = now)

        val first = BrewSessionRepository.claimBoil(batchId, from = 61, to = 60)
        val second = BrewSessionRepository.claimBoil(batchId, from = 61, to = 60)

        first.shouldBeTrue()
        second.shouldBeFalse()
        BrewSessionRepository.find(batchId)!!.timer!!.announcedDownTo shouldBe 60
    }

    @Test
    fun `should finish a mash step only once and move on to the next`() {
        val (userId, batchId) = newBatch()
        BrewSessionRepository.open(batchId, userId, chatId = 1, stage = BrewDayStage.MASH)
        BrewSessionRepository.startMashStep(batchId, step = 0, minutes = 15, now = now)
        val endsAt = BrewSessionRepository.find(batchId)!!.timer!!.endsAt

        val first = BrewSessionRepository.finishMashStep(batchId, endsAt, nextStep = 1)
        val second = BrewSessionRepository.finishMashStep(batchId, endsAt, nextStep = 1)

        first.shouldBeTrue()
        second.shouldBeFalse()
        val session = BrewSessionRepository.find(batchId)!!
        session.mashStep shouldBe 1
        session.timer.shouldBeNull()
    }

    @Test
    fun `should keep the stage a brew is already on when it is opened again`() {
        val (userId, batchId) = newBatch()
        BrewSessionRepository.open(batchId, userId, chatId = 1, stage = BrewDayStage.WATER)
        BrewSessionRepository.moveTo(batchId, BrewDayStage.BOIL)

        val reopened = BrewSessionRepository.open(batchId, userId, chatId = 2, stage = BrewDayStage.WATER)

        reopened.stage shouldBe BrewDayStage.BOIL
        reopened.chatId shouldBe 2
    }

    @Test
    fun `should keep a newer panel when an older one is let go`() {
        val (userId, batchId) = newBatch()
        BrewSessionRepository.open(batchId, userId, chatId = 1, stage = BrewDayStage.BOIL)
        BrewSessionRepository.setPanel(batchId, messageId = 11, now = now)

        BrewSessionRepository.dropPanel(batchId, messageId = 10)

        BrewSessionRepository.find(batchId)!!.panelMessageId shouldBe 11
    }

    private fun newBatch(): Pair<Long, Long> {
        val userId = UserRepository.create("brew-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id
        val recipeId = RecipeRepository.create(userId, NewRecipe(name = "Pale")).id
        return userId to BatchRepository.create(userId, NewBatch(recipeId = recipeId))!!.id
    }
}
