package uk.beertool.telegram

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.recipe.BoilAddition
import java.time.Instant

class BrewTimerTest {

    private val start = Instant.parse("2026-10-16T10:00:00Z")
    private val additions = listOf(
        BoilAddition(60, "Magnum 20 g"),
        BoilAddition(15, "Cascade 30 g"),
        BoilAddition(0, "Citra 40 g"),
    )

    private fun boil(announcedDownTo: Int = 61) = BrewTimer(TimerKind.BOIL, endsAt = start.plusSeconds(60 * 60), announcedDownTo = announcedDownTo)

    @Test
    fun `should call the bittering addition the moment the boil starts`() {
        val timer = boil()

        val alert = timer.boilAlertAt(start, additions)

        alert shouldBe BoilAlert(listOf(BoilAddition(60, "Magnum 20 g")), flameOut = false, announcedDownTo = 60)
    }

    @Test
    fun `should stay quiet between additions`() {
        val timer = boil(announcedDownTo = 60)

        val alert = timer.boilAlertAt(start.plusSeconds(30 * 60), additions)

        alert.shouldBeNull()
    }

    @Test
    fun `should call each addition only once`() {
        val timer = boil(announcedDownTo = 15)

        val alert = timer.boilAlertAt(start.plusSeconds(50 * 60), additions)

        alert.shouldBeNull()
    }

    @Test
    fun `should call missed additions together with flame-out after a late tick`() {
        val timer = boil(announcedDownTo = 60)

        val alert = timer.boilAlertAt(start.plusSeconds(61 * 60), additions)

        alert shouldBe BoilAlert(
            listOf(BoilAddition(15, "Cascade 30 g"), BoilAddition(0, "Citra 40 g")),
            flameOut = true,
            announcedDownTo = 0,
        )
    }

    @Test
    fun `should round the time left up to whole minutes`() {
        val timer = boil()

        val left = timer.minutesLeft(start.plusSeconds(15 * 60 + 30))

        left shouldBe 45
    }

    @Test
    fun `should fall due on the minute a mash step ends and not before`() {
        val step = BrewTimer(TimerKind.MASH, endsAt = start.plusSeconds(40 * 60))

        val early = step.isDue(start.plusSeconds(40 * 60 - 1))
        val onTime = step.isDue(start.plusSeconds(40 * 60))

        early shouldBe false
        onTime shouldBe true
    }
}
