package uk.beertool.batch

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.brewing.WaterProfile
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.RecipeWater
import uk.beertool.recipe.WaterSnapshot
import uk.beertool.recipe.snapshot
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.UserRepository
import uk.beertool.user.WaterSource
import java.time.LocalDate
import java.util.UUID

class BrewWaterTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    private val tap = WaterSource("London tap", WaterProfile(calcium = 99.0, bicarbonate = 250.0))
    private val ashbeck = WaterSource("Ashbeck", WaterProfile(calcium = 11.0, bicarbonate = 25.0))
    private val planned = RecipeWater(source = "London tap", blendSource = "Ashbeck", blendPercent = 50.0, lacticMl = 3.0)

    @Test
    fun `should copy the recipe's water and its sources' numbers onto a new brew`() {
        val userId = brewer(tap, ashbeck)
        val recipeId = recipe(userId, planned)

        val brew = BatchRepository.startBrewDay(recipeId, userId, LocalDate.of(2026, 10, 18))!!

        brew.water shouldBe WaterSnapshot(planned, listOf(tap, ashbeck))
    }

    @Test
    fun `should keep a brew's water as it was when Settings change afterwards`() {
        val userId = brewer(tap, ashbeck)
        val brew = BatchRepository.startBrewDay(recipe(userId, planned), userId, LocalDate.of(2026, 10, 18))!!

        UserRepository.updatePreferences(userId, BrewerPreferences(waterSources = listOf(tap.copy(profile = WaterProfile(calcium = 120.0)))))

        BatchRepository.findById(brew.id, userId)!!.water!!.sources.first().profile.calcium shouldBe 99.0
    }

    @Test
    fun `should leave a brew without water when its recipe has none`() {
        val userId = brewer(tap)

        val brew = BatchRepository.startBrewDay(recipe(userId, water = null), userId, LocalDate.of(2026, 10, 18))!!

        brew.water.shouldBeNull()
    }

    @Test
    fun `should let the brewer switch water on brew day while there is no OG`() {
        val userId = brewer(tap, ashbeck)
        val brew = BatchRepository.startBrewDay(recipe(userId, planned), userId, LocalDate.of(2026, 10, 18))!!
        val tapOnly = BrewerPreferences(waterSources = listOf(tap, ashbeck)).snapshot(RecipeWater(source = "London tap", lacticMl = 9.0))

        val updated = BatchRepository.updateWater(brew.id, userId, tapOnly)

        updated!!.water shouldBe WaterSnapshot(RecipeWater(source = "London tap", lacticMl = 9.0), listOf(tap))
    }

    @Test
    fun `should lock a brew's water once its OG is recorded`() {
        val userId = brewer(tap, ashbeck)
        val recipeId = recipe(userId, planned)
        val brew = BatchRepository.startBrewDay(recipeId, userId, LocalDate.of(2026, 10, 18))!!
        BatchRepository.update(brew.id, userId, NewBatch(recipeId = recipeId, brewDate = brew.brewDate, measuredOg = 1.050))

        val refused = BatchRepository.updateWater(brew.id, userId, null)

        refused.shouldBeNull()
        BatchRepository.findById(brew.id, userId)!!.water!!.water shouldBe planned
    }

    @Test
    fun `should keep the water when the brew's readings are edited`() {
        val userId = brewer(tap, ashbeck)
        val recipeId = recipe(userId, planned)
        val brew = BatchRepository.startBrewDay(recipeId, userId, LocalDate.of(2026, 10, 18))!!

        val edited = BatchRepository.update(brew.id, userId, NewBatch(recipeId = recipeId, brewDate = brew.brewDate, notes = "mashed in at 9"))

        edited!!.water!!.water shouldBe planned
    }

    private fun brewer(vararg sources: WaterSource): Long {
        val id = UserRepository.create("w-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id
        UserRepository.updatePreferences(id, BrewerPreferences(waterSources = sources.toList()))
        return id
    }

    private fun recipe(userId: Long, water: RecipeWater?) = RecipeRepository.create(
        userId,
        NewRecipe(name = "Pale", fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5)), water = water),
    ).id
}
