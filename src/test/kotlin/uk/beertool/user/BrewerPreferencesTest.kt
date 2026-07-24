package uk.beertool.user

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.recipe.MashStep

class BrewerPreferencesTest {

    @Test
    fun `should derive post-boil and fermenter volumes from the kit inputs`() {
        val prefs = BrewerPreferences(preBoilVolumeL = 27.0, boilOffL = 5.0, kettleRetentionL = 3.0)

        prefs.postBoilVolumeL shouldBe 22.0
        prefs.fermenterVolumeL shouldBe 19.0
    }

    @Test
    fun `should move both derived volumes when the collected volume changes`() {
        val bigger = BrewerPreferences(preBoilVolumeL = 35.0, boilOffL = 5.0, kettleRetentionL = 3.0)

        bigger.postBoilVolumeL shouldBe 30.0
        bigger.fermenterVolumeL shouldBe 27.0
    }

    @Test
    fun `should round-trip through the stored JSON, defaults and all`() {
        val prefs = BrewerPreferences(
            preBoilVolumeL = 29.0,
            efficiency = 0.68,
            mashSteps = listOf(MashStep(63.0, 45), MashStep(72.0, 15)),
        )

        val restored = PrefsJson.decodeFromString(BrewerPreferences.serializer(), PrefsJson.encodeToString(BrewerPreferences.serializer(), prefs))

        restored shouldBe prefs
    }

    @Test
    fun `should read an empty object as every app default, for a back-filled user`() {
        val restored = PrefsJson.decodeFromString(BrewerPreferences.serializer(), "{}")

        restored shouldBe BrewerPreferences()
    }
}
