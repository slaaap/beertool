package uk.beertool.push

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.brewhouse.Phase
import uk.beertool.brewhouse.Session
import uk.beertool.recipe.BoilAddition
import java.time.Instant

class BrewAlertsTest {

    private val hops = listOf(
        BoilAddition(10, "Magnum 20 g"), BoilAddition(5, "Saaz 30 g"), BoilAddition(5, "Irish moss 1 g"), BoilAddition(0, "Saaz 50 g"),
        BoilAddition(0, "Citra 40 g", whirlpool = true, standMin = 15),
    )

    @Test
    fun `should ask for the grain once the strike water is ready`() {
        val previous = session(Phase.STRIKE)

        val alerts = brewAlerts(previous, session(Phase.DOUGH_IN), hops)

        alerts.map { it.title } shouldBe listOf("Strike water is ready")
    }

    @Test
    fun `should not ask again while the brewhouse stays waiting for the grain`() {
        val previous = session(Phase.DOUGH_IN)

        val alerts = brewAlerts(previous, session(Phase.DOUGH_IN), hops)

        alerts.shouldBeEmpty()
    }

    @Test
    fun `should say what heating stopped for`() {
        val previous = session(Phase.MASH)

        val alerts = brewAlerts(previous, session(Phase.FAULT, fault = "Thermometer lost"), hops)

        alerts.single().body shouldBe "Thermometer lost. Fix it, then Resume or Stop."
    }

    @Test
    fun `should call an addition as the boil passes its minute, naming everything that goes in together`() {
        val previous = session(Phase.BOIL, boilLeftMin = 5.02)

        val alerts = brewAlerts(previous, session(Phase.BOIL, boilLeftMin = 4.98), hops)

        alerts.single().title shouldBe "Add at 5 min"
        alerts.single().body shouldBe "Saaz 30 g, Irish moss 1 g"
    }

    @Test
    fun `should call the first addition as soon as the boil starts`() {
        val previous = session(Phase.BOIL_HEAT)

        val alerts = brewAlerts(previous, session(Phase.BOIL, boilLeftMin = 9.99), hops)

        alerts.map { it.key } shouldBe listOf("boil-10")
    }

    @Test
    fun `should reach flame-out when the boil runs out`() {
        val previous = session(Phase.BOIL, boilLeftMin = 0.02)

        val alerts = brewAlerts(previous, session(Phase.DONE), hops)

        alerts.single().title shouldBe "Flame-out: add now"
    }

    @Test
    fun `should leave the whirlpool additions out of flame-out`() {
        val previous = session(Phase.BOIL, boilLeftMin = 0.02)

        val alerts = brewAlerts(previous, session(Phase.COOLING, targetC = 80.0), hops)

        alerts.map { it.title to it.body } shouldBe listOf(
            "Flame-out: add now" to "Saaz 50 g",
            "Boil done: start chilling" to "The whirlpool additions go in at 80 °C.",
        )
    }

    @Test
    fun `should call the whirlpool additions once the wort reaches their temperature`() {
        val previous = session(Phase.COOLING, targetC = 80.0)

        val alerts = brewAlerts(previous, session(Phase.WHIRLPOOL, whirlpoolLeftMin = 15.0), hops)

        alerts.single().title shouldBe "Whirlpool: chiller off, add now"
        alerts.single().body shouldBe "Citra 40 g. Stand 15 min."
    }

    @Test
    fun `should say when the whirlpool stand has run out`() {
        val previous = session(Phase.WHIRLPOOL, whirlpoolLeftMin = 0.02)

        val alerts = brewAlerts(previous, session(Phase.DONE), hops)

        alerts.single().title shouldBe "Whirlpool stand done"
    }

    @Test
    fun `should stay quiet when the whirlpool stand is stopped early`() {
        val previous = session(Phase.WHIRLPOOL, whirlpoolLeftMin = 9.0)

        val alerts = brewAlerts(previous, session(Phase.DONE), hops)

        alerts.shouldBeEmpty()
    }

    @Test
    fun `should stay quiet about flame-out when the boil was stopped early`() {
        val previous = session(Phase.BOIL, boilLeftMin = 7.5)

        val alerts = brewAlerts(previous, session(Phase.DONE), hops)

        alerts.shouldBeEmpty()
    }

    @Test
    fun `should not repeat additions already past when a paused boil resumes`() {
        val previous = session(Phase.PAUSED, boilLeftMin = 4.5)

        val alerts = brewAlerts(previous, session(Phase.BOIL, boilLeftMin = 4.4), hops)

        alerts.shouldBeEmpty()
    }

    @Test
    fun `should announce nothing that came due before beertool heard an earlier report`() {
        val alerts = brewAlerts(null, session(Phase.BOIL, boilLeftMin = 2.0), hops)

        alerts.shouldBeEmpty()
    }

    private fun session(
        phase: Phase, boilLeftMin: Double? = null, fault: String? = null, targetC: Double? = null, whirlpoolLeftMin: Double? = null,
    ) = Session(
        batchId = 7, phase = phase, boilLeftMin = boilLeftMin, fault = fault, targetC = targetC, whirlpoolLeftMin = whirlpoolLeftMin,
        updatedAt = Instant.parse("2026-11-28T12:00:00Z"),
    )
}
