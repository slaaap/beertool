package uk.beertool.batch

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class BatchStatsTest {

    @Test
    fun `should derive apparent attenuation from the measured gravities`() {
        val batch = batch(og = 1.052, fg = 1.011, abv = 5.4)

        val stats = batch.stats()

        stats.attenuation!! shouldBe (0.788 plusOrMinus 0.001)
        stats.abv shouldBe 5.4
    }

    @Test
    fun `should report the ABV that was stamped, not one recomputed from the gravities`() {

        val batch = batch(og = 1.048, fg = 1.010, abv = 7.5)

        batch.stats().abv shouldBe 7.5

        batch.stats().attenuation!! shouldBe (0.79 plusOrMinus 0.01)
    }

    @Test
    fun `should compute the ABV to stamp from the gravities plus post-wort additions`() {
        measuredAbv(og = 1.048, fg = 1.010, lateAdditionAbv = 2.5)!! shouldBe (7.5 plusOrMinus 0.05)
        measuredAbv(og = 1.048, fg = 1.010, lateAdditionAbv = 0.0)!! shouldBe (5.0 plusOrMinus 0.05)
        measuredAbv(og = 1.048, fg = null, lateAdditionAbv = 2.5).shouldBeNull()
    }

    @Test
    fun `should report no attenuation until both gravities are measured`() {
        batch(og = 1.052, fg = null).stats().attenuation.shouldBeNull()
        batch(og = null, fg = 1.011).stats().attenuation.shouldBeNull()
    }

    private fun batch(og: Double?, fg: Double?, abv: Double? = null) = Batch(
        id = 1,
        recipeId = 1,
        userId = 1,
        brewDate = LocalDate.of(2026, 7, 4),
        packagedDate = null,
        measuredOg = og,
        measuredFg = fg,
        abv = abv,
        mashEfficiency = null,
        createdAt = Instant.EPOCH,
    )
}
