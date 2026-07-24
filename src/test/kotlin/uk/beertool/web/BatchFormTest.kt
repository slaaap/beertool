package uk.beertool.web

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchFormTest {

    @Test
    fun `should map the measured volumes, carry the recipe id, and trim the notes`() {
        val form = BatchForm(
            measuredPreBoilVolumeL = 26.0,
            measuredPostBoilVolumeL = 21.0,
            measuredFermenterVolumeL = 18.0,
            notes = "  cloudy runnings  ",
        )

        val draft = form.toNewBatch(recipeId = 7)

        draft.recipeId shouldBe 7
        draft.measuredPreBoilVolumeL shouldBe 26.0
        draft.measuredPostBoilVolumeL shouldBe 21.0
        draft.measuredFermenterVolumeL shouldBe 18.0
        draft.notes shouldBe "cloudy runnings"
    }

    @Test
    fun `should read a blank notes box as no note`() {
        BatchForm(notes = "   ").toNewBatch(recipeId = 1).notes.shouldBeNull()
    }
}
