package uk.beertool.batch

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.UserRepository
import java.time.LocalDate
import java.util.UUID

class BatchRepositoryTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should log a brew and load it back identically`() {
        val userId = newUser()
        val recipeId = newRecipe(userId)

        val created = BatchRepository.create(userId, sampleDraft(recipeId))
        val loaded = BatchRepository.findById(created!!.id, userId)

        loaded shouldBe created
        created.brewDate shouldBe LocalDate.of(2026, 7, 4)
        created.packagedDate shouldBe LocalDate.of(2026, 7, 18)
        created.measuredOg shouldBe 1.052
        created.measuredFg shouldBe 1.011
    }

    @Test
    fun `should log a brew that has not been packaged or measured yet`() {
        val userId = newUser()
        val recipeId = newRecipe(userId)

        val created = BatchRepository.create(userId, NewBatch(recipeId = recipeId))

        created!!.brewDate.shouldBeNull()
        created.packagedDate.shouldBeNull()
        created.measuredOg.shouldBeNull()
        created.measuredFg.shouldBeNull()
    }

    @Test
    fun `should fill in the packaging date later, on a brew logged without one`() {
        val userId = newUser()
        val recipeId = newRecipe(userId)
        val brewed = BatchRepository.create(userId, NewBatch(recipeId = recipeId, brewDate = LocalDate.of(2026, 7, 4)))!!

        val packaged = BatchRepository.update(
            id = brewed.id,
            userId = userId,
            draft = NewBatch(
                recipeId = recipeId,
                brewDate = brewed.brewDate,
                packagedDate = LocalDate.of(2026, 7, 18),
            ),
        )

        packaged!!.packagedDate shouldBe LocalDate.of(2026, 7, 18)
        packaged.brewDate shouldBe LocalDate.of(2026, 7, 4)
    }

    @Test
    fun `should mark a brew packaged without disturbing its other figures`() {
        val userId = newUser()
        val brewed = BatchRepository.create(
            userId,
            NewBatch(recipeId = newRecipe(userId), brewDate = LocalDate.of(2026, 7, 4), measuredOg = 1.052),
        )!!

        val packaged = BatchRepository.markPackaged(brewed.id, userId, LocalDate.of(2026, 7, 20))

        packaged shouldBe brewed.copy(packagedDate = LocalDate.of(2026, 7, 20))
    }

    @Test
    fun `should not mark another user's brew packaged`() {
        val owner = newUser()
        val brewed = BatchRepository.create(owner, sampleDraft(newRecipe(owner)))!!

        BatchRepository.markPackaged(brewed.id, newUser(), LocalDate.of(2026, 7, 20)).shouldBeNull()
    }

    @Test
    fun `should float brews still in the fermenter above the packaged ones, newest first`() {
        val userId = newUser()
        val recipeId = newRecipe(userId)
        fun log(brewed: String, packaged: String?) = BatchRepository.create(
            userId,
            NewBatch(
                recipeId = recipeId,
                brewDate = LocalDate.parse(brewed),
                packagedDate = packaged?.let(LocalDate::parse),
            ),
        )!!
        log("2026-06-01", "2026-06-20")
        log("2026-07-10", null)
        log("2026-07-01", "2026-07-12")
        log("2026-05-05", null)

        val log = BatchRepository.listByUser(userId).map { it.batch.brewDate.toString() }

        log shouldBe listOf("2026-07-10", "2026-05-05", "2026-07-01", "2026-06-01")
    }

    @Test
    fun `should stamp the mash efficiency the brew achieved, and not restate it when the recipe changes`() {
        val userId = newUser()
        val recipe = RecipeRepository.create(userId, tripelWithFermenterSugar())

        val brewed = BatchRepository.create(userId, NewBatch(recipeId = recipe.id, measuredOg = 1.048))!!

        brewed.mashEfficiency!! shouldBe (0.688 plusOrMinus 0.005)

        RecipeRepository.update(
            recipe.id,
            userId,
            tripelWithFermenterSugar().copy(fermentables = listOf(RecipeFermentable("Pilsner Malt", 10.0))),
        )

        BatchRepository.findById(brewed.id, userId)!!.mashEfficiency shouldBe brewed.mashEfficiency
    }

    @Test
    fun `should stamp ABV including sugar added after the wort`() {
        val userId = newUser()
        val recipe = RecipeRepository.create(userId, tripelWithFermenterSugar())

        val brewed = BatchRepository.create(
            userId,
            NewBatch(recipeId = recipe.id, measuredOg = 1.048, measuredFg = 1.010),
        )!!

        brewed.abv!! shouldBe (7.6 plusOrMinus 0.1)
    }

    @Test
    fun `should not restate a brew's ABV when the recipe is later edited`() {
        val userId = newUser()
        val recipe = RecipeRepository.create(userId, tripelWithFermenterSugar())
        val brewed = BatchRepository.create(
            userId,
            NewBatch(recipeId = recipe.id, measuredOg = 1.048, measuredFg = 1.010),
        )!!

        RecipeRepository.update(
            recipe.id,
            userId,
            tripelWithFermenterSugar().copy(fermentables = listOf(RecipeFermentable("Pilsner Malt", 5.0))),
        )

        BatchRepository.findById(brewed.id, userId)!!.abv!! shouldBe (7.6 plusOrMinus 0.1)
    }

    @Test
    fun `should stamp no ABV until both gravities are known, then stamp it on the edit`() {
        val userId = newUser()
        val recipe = RecipeRepository.create(userId, tripelWithFermenterSugar())
        val brewDay = BatchRepository.create(userId, NewBatch(recipeId = recipe.id, measuredOg = 1.048))!!

        brewDay.abv.shouldBeNull()

        val finished = BatchRepository.update(
            brewDay.id,
            userId,
            NewBatch(recipeId = recipe.id, measuredOg = 1.048, measuredFg = 1.010),
        )

        finished!!.abv!! shouldBe (7.6 plusOrMinus 0.1)
    }

    @Test
    fun `should count brews per recipe for the recipe list`() {
        val userId = newUser()
        val brewed = newRecipe(userId)
        val untouched = newRecipe(userId)
        BatchRepository.create(userId, sampleDraft(brewed))
        BatchRepository.create(userId, sampleDraft(brewed).copy(brewDate = LocalDate.of(2026, 6, 1)))

        val info = BatchRepository.brewInfoByRecipe(userId)

        info[brewed]!!.count shouldBe 2
        info[brewed]!!.lastBrewed shouldBe LocalDate.of(2026, 7, 4)
        info[untouched].shouldBeNull()
    }

    @Test
    fun `should refuse to log a brew against another user's recipe`() {
        val recipeOfOther = newRecipe(newUser())

        BatchRepository.create(newUser(), sampleDraft(recipeOfOther)).shouldBeNull()
    }

    @Test
    fun `should not return a brew owned by a different user`() {
        val owner = newUser()
        val created = BatchRepository.create(owner, sampleDraft(newRecipe(owner)))!!

        BatchRepository.findById(created.id, newUser()).shouldBeNull()
    }

    @Test
    fun `should list only the requesting user's brews, with their recipe names`() {
        val userId = newUser()
        val recipeId = newRecipe(userId, name = "Pale Ale")
        BatchRepository.create(userId, sampleDraft(recipeId))
        BatchRepository.create(userId, sampleDraft(recipeId).copy(brewDate = LocalDate.of(2026, 6, 1)))
        val other = newUser()
        BatchRepository.create(other, sampleDraft(newRecipe(other)))

        val log = BatchRepository.listByUser(userId)

        log shouldHaveSize 2
        log.map { it.recipeName }.toSet() shouldBe setOf("Pale Ale")
        log.first().batch.brewDate shouldBe LocalDate.of(2026, 7, 4)
    }

    @Test
    fun `should list the brews of one recipe only`() {
        val userId = newUser()
        val brewed = newRecipe(userId)
        val untouched = newRecipe(userId)
        BatchRepository.create(userId, sampleDraft(brewed))

        BatchRepository.listByRecipe(brewed, userId) shouldHaveSize 1
        BatchRepository.listByRecipe(untouched, userId) shouldHaveSize 0
    }

    @Test
    fun `should surface the active brew day only until an OG is recorded`() {
        val userId = newUser()
        val recipe = newRecipe(userId)
        val onKettle = BatchRepository.create(userId, NewBatch(recipeId = recipe, brewDate = LocalDate.now()))!!

        BatchRepository.activeBrewDay(recipe, userId)?.id shouldBe onKettle.id

        BatchRepository.update(onKettle.id, userId, NewBatch(recipeId = recipe, measuredOg = 1.050))

        BatchRepository.activeBrewDay(recipe, userId).shouldBeNull()
    }

    @Test
    fun `should update the measured gravities`() {
        val userId = newUser()
        val recipeId = newRecipe(userId)
        val created = BatchRepository.create(userId, NewBatch(recipeId = recipeId))!!

        val updated = BatchRepository.update(userId = userId, id = created.id, draft = sampleDraft(recipeId))

        updated!!.measuredOg shouldBe 1.052
        updated.measuredFg shouldBe 1.011
    }

    @Test
    fun `should delete a brew, but not another user's`() {
        val owner = newUser()
        val created = BatchRepository.create(owner, sampleDraft(newRecipe(owner)))!!

        BatchRepository.delete(created.id, newUser()) shouldBe false
        BatchRepository.delete(created.id, owner) shouldBe true
        BatchRepository.findById(created.id, owner).shouldBeNull()
    }

    @Test
    fun `should persist the measured volumes and notes`() {
        val userId = newUser()
        val recipeId = newRecipe(userId)

        val brew = BatchRepository.create(
            userId,
            NewBatch(
                recipeId = recipeId,
                measuredPreBoilVolumeL = 26.5,
                measuredPostBoilVolumeL = 21.0,
                measuredFermenterVolumeL = 18.0,
                notes = "Cloudy runnings; smelled great.",
            ),
        )!!

        val found = BatchRepository.findById(brew.id, userId)!!
        found.measuredPreBoilVolumeL shouldBe 26.5
        found.measuredPostBoilVolumeL shouldBe 21.0
        found.measuredFermenterVolumeL shouldBe 18.0
        found.notes shouldBe "Cloudy runnings; smelled great."
    }

    @Test
    fun `should stamp mash efficiency against the measured post-boil volume when the day differed from the plan`() {
        val userId = newUser()
        val recipe = RecipeRepository.create(userId, tripelWithFermenterSugar())

        val brew = BatchRepository.create(
            userId,
            NewBatch(recipeId = recipe.id, measuredOg = 1.048, measuredPostBoilVolumeL = 20.0),
        )!!

        brew.mashEfficiency!! shouldBe (0.625 plusOrMinus 0.005)
    }

    private fun tripelWithFermenterSugar() = NewRecipe(
        name = "Tripel",
        postBoilVolumeL = 22.0,
        fermenterVolumeL = 19.0,
        fermentables = listOf(
            RecipeFermentable(name = "Pilsner Malt", amountKg = 5.0),
            RecipeFermentable(
                name = "Dextrose",
                amountKg = 1.0,
                type = FermentableType.SUGAR,
                usage = FermentableUsage.PRIMARY,
            ),
        ),
    )

    @Test
    fun `should number batches from 1 per recipe and resolve them by number, scoped to the user`() {
        val userId = newUser()
        val recipe = newRecipe(userId)

        val b1 = BatchRepository.create(userId, NewBatch(recipeId = recipe, brewDate = LocalDate.of(2026, 6, 1)))!!
        val b2 = BatchRepository.create(userId, NewBatch(recipeId = recipe, brewDate = LocalDate.of(2026, 7, 1)))!!

        b1.no shouldBe 1
        b2.no shouldBe 2

        BatchRepository.findByRecipeAndNo(recipe, 2, userId)!!.id shouldBe b2.id
        BatchRepository.findByRecipeAndNo(recipe, 2, newUser()).shouldBeNull()
    }

    private fun newUser() = UserRepository.create("u-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id

    private fun newRecipe(userId: Long, name: String = "Pale Ale") = RecipeRepository.create(
        userId,
        NewRecipe(name = name, fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5))),
    ).id

    private fun sampleDraft(recipeId: Long) = NewBatch(
        recipeId = recipeId,
        brewDate = LocalDate.of(2026, 7, 4),
        packagedDate = LocalDate.of(2026, 7, 18),
        measuredOg = 1.052,
        measuredFg = 1.011,
    )
}
