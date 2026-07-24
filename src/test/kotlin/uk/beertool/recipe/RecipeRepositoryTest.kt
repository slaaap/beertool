package uk.beertool.recipe

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.user.UserRepository
import java.util.UUID

class RecipeRepositoryTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should create a recipe with its ingredient lines and load it back identically`() {
        val userId = newUser()

        val created = RecipeRepository.create(userId, sampleDraft())
        val loaded = RecipeRepository.findById(created.id, userId)

        loaded shouldBe created
        created.fermentables shouldHaveSize 1
        created.hops shouldHaveSize 1
        created.yeasts shouldHaveSize 1
        created.extras shouldHaveSize 1
        created.hops.first().name shouldBe "Cascade"
    }

    @Test
    fun `should not return a recipe owned by a different user`() {
        val owner = newUser()
        val other = newUser()

        val created = RecipeRepository.create(owner, sampleDraft())

        RecipeRepository.findById(created.id, other).shouldBeNull()
    }

    @Test
    fun `should list only the requesting user's recipes`() {
        val userId = newUser()
        RecipeRepository.create(userId, sampleDraft().copy(name = "First"))
        RecipeRepository.create(userId, sampleDraft().copy(name = "Second"))
        RecipeRepository.create(newUser(), sampleDraft().copy(name = "Someone else's"))

        val summaries = RecipeRepository.listByUser(userId)

        summaries shouldHaveSize 2
        summaries.map { it.name }.toSet() shouldBe setOf("First", "Second")
    }

    @Test
    fun `should delete a recipe and cascade its lines`() {
        val userId = newUser()
        val created = RecipeRepository.create(userId, sampleDraft())

        val deleted = RecipeRepository.delete(created.id, userId)

        deleted shouldBe true
        RecipeRepository.findById(created.id, userId).shouldBeNull()
    }

    @Test
    fun `should not delete another user's recipe`() {
        val owner = newUser()
        val created = RecipeRepository.create(owner, sampleDraft())

        RecipeRepository.delete(created.id, newUser()) shouldBe false
    }

    @Test
    fun `should keep a fermentable's extract percent through an edit`() {
        val userId = newUser()
        val fruit = RecipeFermentable(
            name = "Cherry puree",
            amountKg = 2.0,
            type = FermentableType.FRUIT,
            extractPercent = 13.0,
        )
        val created = RecipeRepository.create(userId, sampleDraft().copy(fermentables = listOf(fruit)))

        created.fermentables.single().extractPercent shouldBe 13.0

        val updated = RecipeRepository.update(created.id, userId, sampleDraft().copy(fermentables = listOf(fruit)))

        updated!!.fermentables.single().extractPercent shouldBe 13.0
    }

    @Test
    fun `should replace the ingredient lines on update`() {
        val userId = newUser()
        val created = RecipeRepository.create(userId, sampleDraft())

        val updated = RecipeRepository.update(created.id, userId, sampleDraft().copy(name = "Renamed", hops = emptyList()))

        updated!!.name shouldBe "Renamed"
        updated.hops.shouldBeEmpty()
        updated.fermentables shouldHaveSize 1
    }

    @Test
    fun `should keep the mash schedule in the order it is run`() {
        val userId = newUser()
        val schedule = listOf(MashStep(52.0, 20), MashStep(63.0, 45), MashStep(72.0, 15), MashStep(78.0, 10))

        val created = RecipeRepository.create(userId, sampleDraft().copy(mashSteps = schedule))
        val loaded = RecipeRepository.findById(created.id, userId)!!

        loaded.mashSteps shouldBe schedule
    }

    @Test
    fun `should replace the mash schedule on update, like any other line`() {
        val userId = newUser()
        val created = RecipeRepository.create(userId, sampleDraft().copy(mashSteps = listOf(MashStep(65.0, 60))))

        RecipeRepository.update(created.id, userId, sampleDraft().copy(mashSteps = listOf(MashStep(67.0, 90))))
        val updated = RecipeRepository.findById(created.id, userId)!!

        updated.mashSteps shouldBe listOf(MashStep(67.0, 90))
    }

    @Test
    fun `should number recipes from 1 per user and resolve them by number within that user`() {
        val alice = newUser()
        val bob = newUser()

        val a1 = RecipeRepository.create(alice, sampleDraft().copy(name = "Alice One"))
        val a2 = RecipeRepository.create(alice, sampleDraft().copy(name = "Alice Two"))
        val b1 = RecipeRepository.create(bob, sampleDraft().copy(name = "Bob One"))

        a1.no shouldBe 1
        a2.no shouldBe 2
        b1.no shouldBe 1

        RecipeRepository.findByNo(1, alice)!!.name shouldBe "Alice One"
        RecipeRepository.findByNo(1, bob)!!.name shouldBe "Bob One"
        RecipeRepository.findByNo(2, bob).shouldBeNull()
    }

    private fun newUser() = UserRepository.create("u-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id

    private fun sampleDraft() = NewRecipe(
        name = "Pale Ale",
        style = "American Pale Ale",
        postBoilVolumeL = 22.0,
        fermenterVolumeL = 19.0,
        efficiency = 0.72,
        boilTimeMin = 60,
        fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5, colourEbc = 6.0)),
        hops = listOf(RecipeHop(name = "Cascade", amountG = 30.0, alphaAcid = 5.5, usage = HopUsage.BOIL, boilTimeMin = 60)),
        yeasts = listOf(RecipeYeast(name = "US-05", attenuation = 0.75)),
        extras = listOf(RecipeExtra(name = "Whirlfloc", amount = 1.0, unit = AmountUnit.EACH, usage = ExtraUsage.BOIL, boilTimeMin = 15)),
    )
}
