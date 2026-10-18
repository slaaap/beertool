package uk.beertool.common

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EnvTest {

    private fun env(vararg pairs: Pair<String, String>): Env = mapOf(*pairs)::get

    @Test
    fun `should read true and false in any case and fall back to the default otherwise`() {
        val env = env("ON" to " TRUE ", "OFF" to "False", "ODD" to "yes")

        val flags = listOf(env.flag("ON", false), env.flag("OFF", true), env.flag("ODD", true), env.flag("UNSET", false))

        flags shouldBe listOf(true, false, true, false)
    }

    @Test
    fun `should treat a blank value as not set`() {
        val env = env("TOKEN" to "   ")

        env.text("TOKEN").shouldBeNull()
    }

    @Test
    fun `should drop the trailing slash from the public address`() {
        val env = env("PUBLIC_URL" to " https://beer.example.com/ ")

        env.publicUrl() shouldBe "https://beer.example.com"
    }
}
