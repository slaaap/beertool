package uk.beertool.brewhouse

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import uk.beertool.user.User
import java.time.Instant

class BrewhouseServiceTest {

    @Test
    fun `should still answer the brewhouse when working out its alerts fails`() {
        val service = BrewhouseService(onReport = { _, _, _ -> error("the database went away") })
        val brewer = User(1, "brewer@beertool.test", "Brewer", Instant.EPOCH)

        val response = service.sync(brewer, Report(software = "test", ready = true, session = null, commandResults = emptyList()))

        response.commands.shouldBeEmpty()
    }
}
