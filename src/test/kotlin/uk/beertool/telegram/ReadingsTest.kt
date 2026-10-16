package uk.beertool.telegram

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import uk.beertool.batch.NewBatch

class ReadingsTest {

    @Test
    fun `should read several figures sent in one message`() {
        val text = "og 1.052, post 23.5 fermenter 21"

        val parsed = parseReadings(text)

        parsed shouldBe ReadingsParse.Ok(Readings(og = 1.052, postBoilL = 23.5, fermenterL = 21.0))
    }

    @Test
    fun `should read gravity written as points`() {
        val text = "FG: 1010"

        val parsed = parseReadings(text)

        parsed shouldBe ReadingsParse.Ok(Readings(fg = 1.010))
    }

    @Test
    fun `should accept a decimal comma and long key names`() {
        val text = "preboil 27,5 L"

        val parsed = parseReadings(text)

        parsed shouldBe ReadingsParse.Ok(Readings(preBoilL = 27.5))
    }

    @Test
    fun `should keep everything after note as the note`() {
        val text = "og 1.050 note smelled great, og looked low"

        val parsed = parseReadings(text)

        parsed shouldBe ReadingsParse.Ok(Readings(og = 1.050, note = "smelled great, og looked low"))
    }

    @Test
    fun `should refuse a gravity that cannot be real`() {
        val text = "og 5"

        val parsed = parseReadings(text)

        parsed.shouldBeInstanceOf<ReadingsParse.Invalid>()
    }

    @Test
    fun `should not mistake chatter for readings`() {
        val text = "how is the og looking"

        val parsed = parseReadings(text)

        parsed shouldBe ReadingsParse.NotReadings
    }

    @Test
    fun `should add new figures to a batch without losing the ones already logged`() {
        val draft = NewBatch(recipeId = 1, measuredOg = 1.050, measuredPostBoilVolumeL = 23.0, notes = "mashed in at 9")

        val updated = Readings(fg = 1.011, note = "dry hopped").applyTo(draft)

        updated shouldBe draft.copy(measuredFg = 1.011, notes = "mashed in at 9\ndry hopped")
    }
}
