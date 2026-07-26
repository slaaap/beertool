package uk.beertool.web

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class BeerStylesTest {

    @Test
    fun `should load the full BJCP catalogue from resources`() {
        BeerStyles.all.size shouldBeGreaterThan 100
        BeerStyles.all.map { it.name } shouldContain "American IPA"
    }

    @Test
    fun `should resolve a BJCP style to its guideline url`() {
        BeerStyles.urlFor("American IPA") shouldBe "https://www.bjcp.org/style/2021/21/21A/american-ipa/"
    }

    @Test
    fun `should have no url for a custom non-BJCP style`() {
        BeerStyles.urlFor("Wheat IPA").shouldBeNull()
    }

    @Test
    fun `should have no url for an unknown or absent style`() {
        BeerStyles.urlFor("Totally Made Up").shouldBeNull()
        BeerStyles.urlFor(null).shouldBeNull()
    }

    @Test
    fun `should expose a name-to-url map as json for the client`() {
        BeerStyles.nameToUrlJson shouldContain "\"American IPA\""
        BeerStyles.nameToUrlJson shouldContain "bjcp.org"
    }
}
