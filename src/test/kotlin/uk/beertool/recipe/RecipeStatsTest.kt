package uk.beertool.recipe

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import uk.beertool.brewing.Calculators
import uk.beertool.brewing.FermentableAddition
import java.time.Instant

class RecipeStatsTest {

    @Test
    fun `should use a fermentable's own extract percent in preference to the type guess`() {
        val guessed = NewRecipe(
            name = "Fruit beer",
            postBoilVolumeL = 20.0, fermenterVolumeL = 20.0,
            fermentables = listOf(RecipeFermentable(name = "Cherry puree", amountKg = 2.0, type = FermentableType.FRUIT)),
        )
        val stated = guessed.copy(
            fermentables = listOf(
                RecipeFermentable(name = "Cherry puree", amountKg = 2.0, type = FermentableType.FRUIT, extractPercent = 13.0),
            ),
        )

        stated.estimatedStats().og shouldBeGreaterThan guessed.estimatedStats().og

        stated.estimatedStats().og shouldBe (1.005 plusOrMinus 0.001)
    }

    @Test
    fun `should not let a fermenter addition raise OG, but should count its alcohol`() {
        val inKettle = NewRecipe(
            name = "Sugar in the boil", postBoilVolumeL = 20.0, fermenterVolumeL = 20.0,
            fermentables = listOf(
                RecipeFermentable(name = "Pale Malt", amountKg = 4.0),
                RecipeFermentable(
                    name = "Dextrose", amountKg = 1.0,
                    type = FermentableType.SUGAR, usage = FermentableUsage.BOIL,
                ),
            ),
        )
        val inFermenter = inKettle.copy(
            fermentables = inKettle.fermentables.map {
                if (it.type == FermentableType.SUGAR) it.copy(usage = FermentableUsage.PRIMARY) else it
            },
        )

        inFermenter.estimatedStats().og shouldBeLessThan inKettle.estimatedStats().og

        inFermenter.estimatedStats().abv shouldBe (inKettle.estimatedStats().abv plusOrMinus 0.2)
    }

    @Test
    fun `should not let kettle retention change OG, IBU or colour — they are concentrations in the wort`() {
        val noLoss = NewRecipe(
            name = "Pale Ale", postBoilVolumeL = 22.0, fermenterVolumeL = 22.0,
            fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 5.0, colourEbc = 6.0)),
            hops = listOf(RecipeHop(name = "Magnum", amountG = 30.0, alphaAcid = 12.0, boilTimeMin = 60)),
        )
        val threeLitresLeftBehind = noLoss.copy(fermenterVolumeL = 19.0)

        threeLitresLeftBehind.estimatedStats().og shouldBe noLoss.estimatedStats().og
        threeLitresLeftBehind.estimatedStats().ibu shouldBe noLoss.estimatedStats().ibu
        threeLitresLeftBehind.estimatedStats().colourEbc shouldBe noLoss.estimatedStats().colourEbc
    }

    @Test
    fun `should get more alcohol from sugar in the fermenter than the same sugar in the kettle`() {
        val inKettle = NewRecipe(
            name = "Tripel", postBoilVolumeL = 22.0, fermenterVolumeL = 19.0,
            fermentables = listOf(
                RecipeFermentable(name = "Pilsner Malt", amountKg = 5.0),
                RecipeFermentable(
                    name = "Candi sugar", amountKg = 1.0,
                    type = FermentableType.SUGAR, usage = FermentableUsage.BOIL,
                ),
            ),
        )
        val inFermenter = inKettle.copy(
            fermentables = inKettle.fermentables.map {
                if (it.type == FermentableType.SUGAR) it.copy(usage = FermentableUsage.PRIMARY) else it
            },
        )

        inFermenter.estimatedStats().abv shouldBeGreaterThan inKettle.estimatedStats().abv
    }

    @Test
    fun `should ignore a conditioning yeast added at packaging when estimating FG`() {
        val bill = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 5.0))
        val primaryOnly = NewRecipe(
            name = "Bottle conditioned", postBoilVolumeL = 20.0, fermenterVolumeL = 20.0,
            fermentables = bill,
            yeasts = listOf(RecipeYeast(name = "US-05", attenuation = 0.75)),
        )
        val alsoBottled = primaryOnly.copy(
            yeasts = primaryOnly.yeasts + RecipeYeast(
                name = "CL23",
                attenuation = 0.95,
                usage = YeastUsage.PACKAGING,
            ),
        )

        alsoBottled.estimatedStats().fg shouldBe primaryOnly.estimatedStats().fg
        alsoBottled.estimatedStats().abv shouldBe primaryOnly.estimatedStats().abv
    }

    @Test
    fun `should apply mash efficiency to grain but not to sugar`() {
        val grain = NewRecipe(
            name = "All grain", postBoilVolumeL = 20.0, fermenterVolumeL = 20.0, efficiency = 0.75,
            fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 1.0, type = FermentableType.MALT)),
        )
        val sugar = grain.copy(
            fermentables = listOf(RecipeFermentable(name = "Dextrose", amountKg = 1.0, type = FermentableType.SUGAR)),
        )

        grain.estimatedStats().og shouldBe (1.0 + 0.60 * 384 / 20 / 1000 plusOrMinus 0.0001)

        sugar.estimatedStats().og shouldBe (1.0 + 1.00 * 384 / 20 / 1000 plusOrMinus 0.0001)
    }

    @Test
    fun `should estimate og abv ibu and colour for a simple pale ale`() {
        val recipe = recipe(
            fermentables = listOf(fermentable(FermentableType.MALT, amountKg = 4.5, colourEbc = 6.0)),
            hops = listOf(hop(amountG = 30.0, alphaAcid = 5.5, boilTimeMin = 60, usage = HopUsage.BOIL)),
            yeasts = listOf(yeast(attenuation = 0.75)),
        )

        val stats = recipe.stats()

        stats.og shouldBe (1.050 plusOrMinus 0.005)
        stats.abv shouldBeGreaterThan 4.0
        stats.ibu shouldBeGreaterThan 0.0
        stats.colourEbc shouldBeIn 6.0..16.0
    }

    @Test
    fun `should not count dry-hop additions towards ibu`() {
        val boilOnly = recipe(hops = listOf(hop(30.0, 5.5, 60, HopUsage.BOIL)))
        val withDryHop = recipe(
            hops = listOf(
                hop(30.0, 5.5, 60, HopUsage.BOIL),
                hop(50.0, 12.0, null, HopUsage.DRY_HOP),
            ),
        )

        withDryHop.stats().ibu shouldBe boilOnly.stats().ibu
    }

    @Test
    fun `should guess a higher extract yield for sugar than grain`() {
        val grainBill = recipe(fermentables = listOf(fermentable(FermentableType.MALT, amountKg = 1.0)))
        val sugarBill = recipe(fermentables = listOf(fermentable(FermentableType.SUGAR, amountKg = 1.0)))

        sugarBill.stats().og shouldBeGreaterThan grainBill.stats().og
    }

    @Test
    fun `should estimate a recipe with no mash schedule exactly as it did before mash steps existed`() {
        val bill = listOf(fermentable(FermentableType.MALT, amountKg = 5.0))
        val yeast = listOf(RecipeYeast(name = "US-05", attenuation = 0.80))

        val noMash = recipe(fermentables = bill, yeasts = yeast, mashSteps = emptyList()).stats()

        val fromYeastAlone = Calculators.estimateFg(
            listOf(FermentableAddition(extractPercent = 80.0, colourEbc = 8.0, massKg = 5.0)),
            volumeL = 20.0,
            efficiency = 0.72,
            attenuation = 0.80,
        )
        noMash.fg shouldBe (fromYeastAlone plusOrMinus 0.0001)
    }

    @Test
    fun `should open a new recipe on this brewer's usual mash`() {
        val draft = NewRecipe(name = "New")

        draft.mashSteps shouldBe listOf(MashStep(62.0, 40), MashStep(72.0, 20))
    }

    @Test
    fun `should finish drier from a low mash than a high one, with the same yeast`() {
        val bill = listOf(fermentable(FermentableType.MALT, amountKg = 5.0))
        val yeast = listOf(RecipeYeast(name = "US-05", attenuation = 0.80))

        val low = recipe(fermentables = bill, yeasts = yeast, mashSteps = listOf(MashStep(63.0, 60))).stats()
        val high = recipe(fermentables = bill, yeasts = yeast, mashSteps = listOf(MashStep(72.0, 60))).stats()

        low.fg shouldBeLessThan high.fg
        low.abv shouldBeGreaterThan high.abv
    }

    private fun recipe(
        fermentables: List<RecipeFermentable> = emptyList(),
        hops: List<RecipeHop> = emptyList(),
        yeasts: List<RecipeYeast> = emptyList(),
        mashSteps: List<MashStep> = emptyList(),
        postBoilVolumeL: Double = 20.0,
        fermenterVolumeL: Double = 20.0,
        efficiency: Double = 0.72,
    ) = Recipe(
        id = 1, userId = 1, name = "Test", style = null, description = null,
        preBoilVolumeL = null, postBoilVolumeL = postBoilVolumeL, fermenterVolumeL = fermenterVolumeL,
        efficiency = efficiency, boilTimeMin = 60, mashSteps = mashSteps,
        fermentables = fermentables, hops = hops, yeasts = yeasts, extras = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun fermentable(
        type: FermentableType,
        amountKg: Double,
        colourEbc: Double? = null,
        extractPercent: Double? = null,
        usage: FermentableUsage = FermentableUsage.MASH,
    ) = RecipeFermentable(
        name = "Malt",
        type = type,
        amountKg = amountKg,
        colourEbc = colourEbc,
        extractPercent = extractPercent,
        usage = usage,
    )

    private fun hop(amountG: Double, alphaAcid: Double, boilTimeMin: Int?, usage: HopUsage) =
        RecipeHop(name = "Hop", amountG = amountG, alphaAcid = alphaAcid, boilTimeMin = boilTimeMin, usage = usage)

    private fun yeast(attenuation: Double, usage: YeastUsage = YeastUsage.PRIMARY) =
        RecipeYeast(name = "Yeast", attenuation = attenuation, usage = usage)
}
