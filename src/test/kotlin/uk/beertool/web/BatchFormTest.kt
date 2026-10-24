package uk.beertool.web

import io.kotest.assertions.throwables.shouldThrow
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

    @Test
    fun `should read gravity typed as points the same as gravity typed in full`() {
        val form = BatchForm(measuredOg = 1052.0, measuredFg = 1.010)

        val draft = form.toNewBatch(recipeId = 1)

        draft.measuredOg shouldBe 1.052
        draft.measuredFg shouldBe 1.010
    }

    @Test
    fun `should refuse a gravity that is neither a gravity nor gravity points`() {
        val form = BatchForm(measuredOg = 52.0)

        val error = shouldThrow<InvalidInput> { form.toNewBatch(recipeId = 1) }

        error.message shouldBe "That OG doesn't look like a gravity. Enter it as 1.052 or 1052."
    }

    @Test
    fun `should refuse a measured volume of zero`() {
        val form = BatchForm(measuredPostBoilVolumeL = 0.0)

        shouldThrow<InvalidInput> { form.toNewBatch(recipeId = 1) }
    }
}
