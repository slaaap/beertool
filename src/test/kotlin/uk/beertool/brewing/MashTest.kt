package uk.beertool.brewing

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MashTest {

    @Test
    fun `should say nothing when the mash was not recorded`() {
        Calculators.mashAttenuationShift(emptyList()) shouldBe null
    }

    @Test
    fun `should leave the reference mash exactly where the yeast put it`() {
        val shift = Calculators.mashAttenuationShift(listOf(MashRest(66.5, 60)))!!

        shift shouldBe (0.0 plusOrMinus 0.0001)
    }

    @Test
    fun `should make a low mash finish drier and a high mash finish sweeter`() {
        val low = Calculators.mashAttenuationShift(listOf(MashRest(63.0, 60)))!!
        val high = Calculators.mashAttenuationShift(listOf(MashRest(72.0, 60)))!!

        low shouldBe (0.059 plusOrMinus 0.005)
        high shouldBe (-0.074 plusOrMinus 0.005)
    }

    @Test
    fun `should read the same two rests differently depending on the order they are run`() {
        val betaFirst = Calculators.mashAttenuationShift(listOf(MashRest(63.0, 45), MashRest(72.0, 20)))!!
        val alphaFirst = Calculators.mashAttenuationShift(listOf(MashRest(72.0, 20), MashRest(63.0, 45)))!!

        betaFirst shouldBeGreaterThan alphaFirst
        (betaFirst - alphaFirst) shouldBeGreaterThan 0.05
    }

    @Test
    fun `should not let a protein rest pretend to be a saccharification rest`() {
        val withProteinRest = Calculators.mashAttenuationShift(listOf(MashRest(52.0, 20), MashRest(65.0, 60)))!!
        val without = Calculators.mashAttenuationShift(listOf(MashRest(65.0, 60)))!!

        withProteinRest shouldBe (without plusOrMinus 0.005)
    }

    @Test
    fun `should refuse to judge a mash that never converted anything`() {

        Calculators.mashAttenuationShift(listOf(MashRest(40.0, 30))) shouldBe null
        Calculators.fermentabilityIndex(listOf(MashRest(40.0, 30))) shouldBe null
    }

    @Test
    fun `should let a mash-out cost a little fermentability, but only a little`() {
        val plain = Calculators.mashAttenuationShift(listOf(MashRest(65.0, 60)))!!
        val mashedOut = Calculators.mashAttenuationShift(listOf(MashRest(65.0, 60), MashRest(78.0, 10)))!!

        mashedOut shouldBeLessThan plain
        (plain - mashedOut) shouldBeLessThan 0.02
    }

    @Test
    fun `should hold a step mash between the two rests it is made of`() {
        val dry = Calculators.mashAttenuationShift(listOf(MashRest(63.0, 60)))!!
        val sweet = Calculators.mashAttenuationShift(listOf(MashRest(72.0, 60)))!!
        val stepped = Calculators.mashAttenuationShift(listOf(MashRest(63.0, 40), MashRest(72.0, 20)))!!

        stepped shouldBeLessThan dry
        stepped shouldBeGreaterThan sweet
    }
}
