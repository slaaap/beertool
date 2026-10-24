package uk.beertool.auth

import java.time.Duration
import java.time.Instant

// Failed attempts per key over a sliding window; held in memory, so a restart forgives everyone.
class AttemptLimiter(private val maxFailures: Int, private val window: Duration) {
    private val failures = HashMap<String, ArrayDeque<Instant>>()

    @Synchronized
    fun blocked(key: String, now: Instant = Instant.now()) = recent(key, now).size >= maxFailures

    @Synchronized
    fun fail(key: String, now: Instant = Instant.now()) {
        if (failures.size >= MAX_KEYS) failures.keys.toList().forEach { recent(it, now) }
        failures.getOrPut(key) { ArrayDeque() }.addLast(now)
    }

    @Synchronized
    fun forgive(key: String) {
        failures.remove(key)
    }

    private fun recent(key: String, now: Instant): ArrayDeque<Instant> {
        val times = failures[key] ?: return ArrayDeque()
        while (times.isNotEmpty() && !times.first().isAfter(now - window)) times.removeFirst()
        if (times.isEmpty()) failures.remove(key)
        return times
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}

object LoginThrottle {
    // An address gets a handful of guesses; an account gets more, so one attacker can't easily lock its owner out.
    private val byAddress = AttemptLimiter(maxFailures = 10, window = Duration.ofMinutes(15))
    private val byEmail = AttemptLimiter(maxFailures = 50, window = Duration.ofMinutes(15))

    fun blocked(address: String, email: String) = byAddress.blocked(address) || byEmail.blocked(email)

    fun fail(address: String, email: String) {
        byAddress.fail(address)
        byEmail.fail(email)
    }

    fun succeed(address: String, email: String) {
        byAddress.forgive(address)
        byEmail.forgive(email)
    }
}
