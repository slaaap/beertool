package uk.beertool.brewhouse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class BrewhousesTest {

    private var clock = Instant.parse("2026-11-25T18:00:00Z")
    private val brewhouses = Brewhouses { clock }

    @Test
    fun `should keep the latest report and count the brewhouse online right after a sync`() {
        val report = report(software = "beertool-brewhouse 1.1")

        brewhouses.sync(USER, report)

        val status = brewhouses.status(USER)
        status.online shouldBe true
        status.lastSeenAt shouldBe clock
        status.report shouldBe report
    }

    @Test
    fun `should report a brewhouse that never synced as offline with no report`() {
        val status = brewhouses.status(USER)

        status.online shouldBe false
        status.lastSeenAt.shouldBeNull()
        status.report.shouldBeNull()
    }

    @Test
    fun `should count the brewhouse offline once its last sync is 15 seconds old`() {
        brewhouses.sync(USER, report())

        later(Duration.ofSeconds(15))

        brewhouses.status(USER).online shouldBe false
    }

    @Test
    fun `should deliver pending commands in every sync, oldest first, until acknowledged`() {
        brewhouses.sync(USER, report())
        val pause = brewhouses.queue(USER, CommandType.PAUSE)
        later(Duration.ofSeconds(1))
        val resume = brewhouses.queue(USER, CommandType.RESUME)

        val first = brewhouses.sync(USER, report())
        val second = brewhouses.sync(USER, report())

        first.map { it.id } shouldBe listOf(pause.id, resume.id)
        second.map { it.id } shouldBe listOf(pause.id, resume.id)
    }

    @Test
    fun `should stop delivering a command once acknowledged, and record what the brewhouse said`() {
        brewhouses.sync(USER, report())
        val pause = brewhouses.queue(USER, CommandType.PAUSE)
        val boil = brewhouses.queue(USER, CommandType.START_BOIL)

        val pending = brewhouses.sync(USER, report(results = listOf(CommandResult(pause.id, ok = true), CommandResult(boil.id, ok = false, error = "not mashed yet"))))

        pending.shouldBeEmpty()
        brewhouses.command(USER, pause.id)!!.status shouldBe CommandStatus.DONE
        brewhouses.command(USER, boil.id)!!.status shouldBe CommandStatus.REFUSED
        brewhouses.command(USER, boil.id)!!.error shouldBe "not mashed yet"
    }

    @Test
    fun `should expire a command nobody acknowledged within 60 seconds, so it never fires late`() {
        brewhouses.sync(USER, report())
        val stop = brewhouses.queue(USER, CommandType.STOP)

        later(Duration.ofSeconds(60))
        val pending = brewhouses.sync(USER, report())

        pending.shouldBeEmpty()
        brewhouses.command(USER, stop.id)!!.status shouldBe CommandStatus.EXPIRED
    }

    @Test
    fun `should keep a finished command for 10 minutes and then forget it`() {
        brewhouses.sync(USER, report())
        val stop = brewhouses.queue(USER, CommandType.STOP)

        later(Duration.ofMinutes(10))
        val kept = brewhouses.command(USER, stop.id)
        later(Duration.ofSeconds(1))
        val forgotten = brewhouses.command(USER, stop.id)

        kept!!.status shouldBe CommandStatus.EXPIRED
        forgotten.shouldBeNull()
    }

    @Test
    fun `should refuse to queue a command while the brewhouse is offline`() {
        brewhouses.sync(USER, report())

        later(Duration.ofSeconds(20))
        val problem = shouldThrow<BrewhouseProblem> { brewhouses.queue(USER, CommandType.PAUSE) }

        problem.status shouldBe 409
    }

    @Test
    fun `should refuse to start while the brewhouse reports it is not ready`() {
        brewhouses.sync(USER, report(ready = false))

        val problem = shouldThrow<BrewhouseProblem> { brewhouses.queue(USER, CommandType.START) }

        problem.status shouldBe 409
    }

    @Test
    fun `should keep one brewer's commands away from another's brewhouse`() {
        brewhouses.sync(USER, report())
        brewhouses.sync(OTHER_USER, report())
        val pause = brewhouses.queue(USER, CommandType.PAUSE)

        val theirs = brewhouses.sync(OTHER_USER, report())

        theirs.shouldBeEmpty()
        brewhouses.command(OTHER_USER, pause.id).shouldBeNull()
    }

    private fun later(by: Duration) {
        clock = clock.plus(by)
    }

    private companion object {
        const val USER = 1L
        const val OTHER_USER = 2L
    }
}

internal fun report(
    software: String = "beertool-brewhouse 1.0",
    ready: Boolean = true,
    session: Session? = null,
    results: List<CommandResult> = emptyList(),
) = Report(
    software = software,
    devices = Devices(Hob(online = true, mode = HobMode.STANDBY), Thermometer(online = true, topC = 18.5, bottomC = 18.4), Pump(online = true, on = false)),
    ready = ready,
    session = session,
    commandResults = results,
)
