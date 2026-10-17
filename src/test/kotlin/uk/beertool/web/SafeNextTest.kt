package uk.beertool.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SafeNextTest {

    @Test
    fun `should keep a plain path on this site after login`() {
        val paths = listOf("/recipes", "/oauth/authorize?client_id=https%3A%2F%2Fclaude.ai%2Fx&state=1")

        val kept = paths.map { safeNext(it) }

        kept shouldBe paths
    }

    @Test
    fun `should refuse anything a browser could read as another site`() {
        val attacks = listOf(
            "//evil.example",
            "/\t/evil.example",
            "/\n/evil.example",
            "/\r/evil.example",
            "/\\evil.example",
            "https://evil.example",
            "javascript:alert(1)",
            "evil.example",
            "/ /evil.example",
        )

        val kept = attacks.map { safeNext(it) }

        kept shouldBe attacks.map { null }
    }
}
