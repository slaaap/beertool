package uk.beertool.recipe

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.user.UserRepository
import java.util.UUID

class RecipeSearchTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should find a recipe by a hop it uses`() {
        val userId = newUser()
        seedLibrary(userId)

        val found = RecipeRepository.search(userId, "citra")

        found.map { it.name } shouldContainExactly listOf("Hazy IPA")
    }

    @Test
    fun `should find a recipe by a malt it uses`() {
        val userId = newUser()
        seedLibrary(userId)

        val found = RecipeRepository.search(userId, "roasted barley")

        found.map { it.name } shouldContainExactly listOf("Dry Stout")
    }

    @Test
    fun `should match on a word prefix, so a half-typed hop still finds it`() {
        val userId = newUser()
        seedLibrary(userId)

        RecipeRepository.search(userId, "cit").map { it.name } shouldContainExactly listOf("Hazy IPA")
    }

    @Test
    fun `should find a recipe by name and by style`() {
        val userId = newUser()
        seedLibrary(userId)

        RecipeRepository.search(userId, "stout").map { it.name } shouldContainExactly listOf("Dry Stout")
        RecipeRepository.search(userId, "saison").map { it.name } shouldContainExactly listOf("Farmhouse")
    }

    @Test
    fun `should rank a name match above an ingredient match`() {
        val userId = newUser()
        RecipeRepository.create(userId, draft("Cascade Pale Ale", hop = "Fuggles"))
        RecipeRepository.create(userId, draft("Bitter", hop = "Cascade"))

        val found = RecipeRepository.search(userId, "cascade")

        found shouldHaveSize 2
        found.first().name shouldBe "Cascade Pale Ale"
    }

    @Test
    fun `should require every word to match`() {
        val userId = newUser()
        seedLibrary(userId)

        RecipeRepository.search(userId, "citra stout").shouldBeEmpty()
    }

    @Test
    fun `should search only the requesting user's recipes`() {
        val owner = newUser()
        RecipeRepository.create(owner, draft("Hazy IPA", hop = "Citra"))

        RecipeRepository.search(newUser(), "citra").shouldBeEmpty()
    }

    @Test
    fun `should return the whole list for a blank term`() {
        val userId = newUser()
        seedLibrary(userId)

        RecipeRepository.search(userId, "   ") shouldHaveSize 3
    }

    @Test
    fun `should ignore tsquery punctuation rather than blowing up on it`() {
        val userId = newUser()
        seedLibrary(userId)

        RecipeRepository.search(userId, "citra & !:*") shouldHaveSize 1

        RecipeRepository.search(userId, "!!!") shouldHaveSize 3
    }

    @Test
    fun `should find an accented ingredient — the sanitiser must not strip non-ASCII letters`() {
        val userId = newUser()
        RecipeRepository.create(userId, draft("Festbier", hop = "Hallertauer Mittelfrüh"))

        RecipeRepository.search(userId, "mittelfrüh") shouldHaveSize 1
        RecipeRepository.search(userId, "mittelfr") shouldHaveSize 1
    }

    @Test
    fun `should find a hyphenated yeast by its full code`() {
        val userId = newUser()
        RecipeRepository.create(userId, draft("House Ale").copy(yeasts = listOf(RecipeYeast("US-05"))))

        RecipeRepository.search(userId, "us-05") shouldHaveSize 1
    }

    @Test
    fun `should follow a recipe when its ingredients are edited`() {
        val userId = newUser()
        val recipe = RecipeRepository.create(userId, draft("Experiment", hop = "Citra"))

        RecipeRepository.update(recipe.id, userId, draft("Experiment", hop = "Mosaic"))

        RecipeRepository.search(userId, "citra").shouldBeEmpty()
        RecipeRepository.search(userId, "mosaic") shouldHaveSize 1
    }

    private fun seedLibrary(userId: Long) {
        RecipeRepository.create(userId, draft("Hazy IPA", style = "New England IPA", hop = "Citra"))
        RecipeRepository.create(userId, draft("Dry Stout", style = "Irish Stout", malt = "Roasted Barley"))
        RecipeRepository.create(userId, draft("Farmhouse", style = "Saison", hop = "Saaz"))
    }

    private fun newUser() = UserRepository.create("u-${UUID.randomUUID()}@beertool.test", "hash", "Brewer").id

    private fun draft(
        name: String,
        style: String? = null,
        malt: String = "Pale Malt",
        hop: String = "Fuggles",
    ) = NewRecipe(
        name = name,
        style = style,
        fermentables = listOf(RecipeFermentable(name = malt, amountKg = 4.0)),
        hops = listOf(RecipeHop(name = hop, amountG = 30.0, alphaAcid = 6.0, boilTimeMin = 60)),
        yeasts = listOf(RecipeYeast(name = "US-05")),
    )
}
