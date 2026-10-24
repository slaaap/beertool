package uk.beertool.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AttemptLimiterTest {

    private val start = Instant.parse("2026-10-24T12:00:00Z")

    @Test
    fun `should block a key once it reaches the limit of failures`() {
        val limiter = AttemptLimiter(maxFailures = 3, window = Duration.ofMinutes(15))

        repeat(3) { limiter.fail("1.2.3.4", start) }

        limiter.blocked("1.2.3.4", start) shouldBe true
        limiter.blocked("5.6.7.8", start) shouldBe false
    }

    @Test
    fun `should forget failures once they slide out of the window`() {
        val limiter = AttemptLimiter(maxFailures = 3, window = Duration.ofMinutes(15))
        repeat(2) { limiter.fail("1.2.3.4", start) }

        limiter.fail("1.2.3.4", start + Duration.ofMinutes(10))

        limiter.blocked("1.2.3.4", start + Duration.ofMinutes(12)) shouldBe true
        limiter.blocked("1.2.3.4", start + Duration.ofMinutes(16)) shouldBe false
    }

    @Test
    fun `should clear a key that is forgiven`() {
        val limiter = AttemptLimiter(maxFailures = 1, window = Duration.ofMinutes(15))
        limiter.fail("1.2.3.4", start)

        limiter.forgive("1.2.3.4")

        limiter.blocked("1.2.3.4", start) shouldBe false
    }
}
