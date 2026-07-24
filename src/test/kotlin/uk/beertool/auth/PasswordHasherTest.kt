package uk.beertool.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class PasswordHasherTest {

    @Test
    fun `should verify a correct password against its hash`() {
        val password = "correct horse battery staple"

        val hash = PasswordHasher.hash(password)

        PasswordHasher.verify(password, hash) shouldBe true
    }

    @Test
    fun `should reject an incorrect password`() {
        val hash = PasswordHasher.hash("correct horse battery staple")

        val verified = PasswordHasher.verify("Tr0ub4dour", hash)

        verified shouldBe false
    }

    @Test
    fun `should produce a different hash from the raw password`() {
        val password = "correct horse battery staple"

        val hash = PasswordHasher.hash(password)

        hash shouldNotBe password
    }

    @Test
    fun `should salt so the same password hashes differently each time`() {
        val password = "correct horse battery staple"

        val first = PasswordHasher.hash(password)
        val second = PasswordHasher.hash(password)

        first shouldNotBe second
    }

    @Test
    fun `should verify a stored hash it did not produce in this run`() {

        val storedHash = "\$2a\$12\$JnyMrtrvKoXzUBHq346PjugSVoUwpPLC2hf0xFUuVyy5GFm1qaKfW"

        PasswordHasher.verify("brewbrew", storedHash) shouldBe true
        PasswordHasher.verify("wrong", storedHash) shouldBe false
    }

    @Test
    fun `should hash at the configured cost`() {
        PasswordHasher.hash("correct horse battery staple") shouldStartWith "\$2a\$12\$"
    }
}
