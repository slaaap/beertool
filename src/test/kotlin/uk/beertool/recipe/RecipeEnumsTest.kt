package uk.beertool.recipe

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RecipeEnumsTest {

    @Test
    fun `should offer malt only the mash, and sugar everything from the kettle onwards`() {
        FermentableType.MALT.usages shouldContainExactly listOf(FermentableUsage.MASH)
        FermentableType.UNMALTED.usages shouldContainExactly listOf(FermentableUsage.MASH)
        FermentableType.EXTRACT.usages shouldContainExactly listOf(FermentableUsage.BOIL)
        FermentableType.SUGAR.usages shouldContainExactly (FermentableUsage.all - FermentableUsage.MASH)
    }

    @Test
    fun `should not offer fruit the mash or the whirlpool`() {
        val fruit = FermentableType.FRUIT.usages

        fruit.contains(FermentableUsage.MASH) shouldBe false
        fruit.contains(FermentableUsage.WHIRLPOOL) shouldBe false
        fruit.contains(FermentableUsage.SECONDARY) shouldBe true
        fruit.contains(FermentableUsage.PACKAGING) shouldBe true
    }

    @Test
    fun `should give a time only to what steeps in hot wort`() {
        FermentableUsage.BOIL.takesTime shouldBe true
        FermentableUsage.SECONDARY.takesTime shouldBe false
        FermentableUsage.MASH.takesTime shouldBe false

        HopUsage.WHIRLPOOL.takesTime shouldBe true
        HopUsage.DRY_HOP.takesTime shouldBe false
    }

    @Test
    fun `should count as in the boil only what the boil actually contains`() {
        HopUsage.FIRST_WORT.inBoil shouldBe true
        HopUsage.BOIL.inBoil shouldBe true
        FermentableUsage.BOIL.inBoil shouldBe true
        ExtraUsage.BOIL.inBoil shouldBe true

        HopUsage.WHIRLPOOL.takesTime shouldBe true
        HopUsage.WHIRLPOOL.inBoil shouldBe false
        ExtraUsage.WHIRLPOOL.inBoil shouldBe false
        HopUsage.DRY_HOP.inBoil shouldBe false
        FermentableUsage.MASH.inBoil shouldBe false
    }

    @Test
    fun `should bitter with exactly the hops that sit in the boil`() {
        HopUsage.all.forEach { it.bitters shouldBe it.inBoil }
    }

    @Test
    fun `should count only malt as bringing its own enzymes`() {

        FermentableType.MALT.hasEnzymes shouldBe true
        FermentableType.UNMALTED.hasEnzymes shouldBe false

        FermentableType.MALT.isMashed shouldBe true
        FermentableType.UNMALTED.isMashed shouldBe true
    }

    @Test
    fun `should measure the unmalted share against the grist, not the whole bill`() {
        val bill = listOf(
            RecipeFermentable(name = "Pilsner malt", amountKg = 3.0, type = FermentableType.MALT),
            RecipeFermentable(name = "Flaked oats", amountKg = 1.0, type = FermentableType.UNMALTED),

            RecipeFermentable(
                name = "Candi sugar", amountKg = 4.0,
                type = FermentableType.SUGAR, usage = FermentableUsage.BOIL,
            ),
        )

        NewRecipe(name = "Oat beer", fermentables = bill).unmaltedFraction() shouldBe 0.25
    }

    @Test
    fun `should not warn about an all-malt bill, and should warn about one the malt cannot convert`() {
        fun bill(oatsKg: Double) = NewRecipe(
            name = "Test",
            fermentables = listOf(
                RecipeFermentable(name = "Pilsner malt", amountKg = 5.0, type = FermentableType.MALT),
                RecipeFermentable(name = "Flaked oats", amountKg = oatsKg, type = FermentableType.UNMALTED),
            ),
        )

        (bill(0.0).unmaltedFraction() > MAX_UNMALTED_FRACTION) shouldBe false
        (bill(1.0).unmaltedFraction() > MAX_UNMALTED_FRACTION) shouldBe false
        (bill(6.0).unmaltedFraction() > MAX_UNMALTED_FRACTION) shouldBe true
    }

    @Test
    fun `should let every fermentable type reach the usages this brewer's 163 recipes actually used`() {
        FermentableType.MALT.usages shouldContainExactly listOf(FermentableUsage.MASH)
        FermentableType.SUGAR.usages.containsAll(
            listOf(FermentableUsage.BOIL, FermentableUsage.SECONDARY, FermentableUsage.PACKAGING),
        ) shouldBe true
        FermentableType.FRUIT.usages.containsAll(
            listOf(FermentableUsage.PRIMARY, FermentableUsage.SECONDARY, FermentableUsage.PACKAGING),
        ) shouldBe true
    }
}
