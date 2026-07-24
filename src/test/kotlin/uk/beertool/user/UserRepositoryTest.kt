package uk.beertool.user

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.MashStep
import java.util.UUID

class UserRepositoryTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
    }

    @Test
    fun `should create a user and find it by email with its hash`() {
        val email = uniqueEmail()

        val created = UserRepository.create(email, "hashed-secret", "Ada")
        val found = UserRepository.findByEmail(email)

        found?.user shouldBe created
        found?.passwordHash shouldBe "hashed-secret"
    }

    @Test
    fun `should find a created user by id without exposing its hash`() {
        val created = UserRepository.create(uniqueEmail(), "hashed-secret", "Grace")

        val found = UserRepository.findById(created.id)

        found shouldBe created
    }

    @Test
    fun `should return null when looking up an unknown email`() {
        val found = UserRepository.findByEmail(uniqueEmail())

        found.shouldBeNull()
    }

    @Test
    fun `should give a new user the app-default preferences`() {
        val created = UserRepository.create(uniqueEmail(), "hashed-secret", "Fritz")

        created.preferences shouldBe BrewerPreferences()
        UserRepository.findById(created.id)?.preferences shouldBe BrewerPreferences()
    }

    @Test
    fun `should persist edited preferences and read them back`() {
        val created = UserRepository.create(uniqueEmail(), "hashed-secret", "Ninkasi")
        val edited = BrewerPreferences(
            preBoilVolumeL = 35.0,
            efficiency = 0.68,
            mashSteps = listOf(MashStep(66.0, 60)),
        )

        val updated = UserRepository.updatePreferences(created.id, edited)

        updated?.preferences shouldBe edited
        UserRepository.findById(created.id)?.preferences shouldBe edited
    }

    private fun uniqueEmail() = "user-${UUID.randomUUID()}@beertool.test"
}
