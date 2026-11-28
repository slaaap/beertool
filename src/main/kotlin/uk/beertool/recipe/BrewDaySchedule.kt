package uk.beertool.recipe

import uk.beertool.common.formatAmount

// A whirlpool addition goes in after the boil, once the wort has cooled, and stands for standMin.
data class BoilAddition(val minutesBeforeEnd: Int, val what: String, val whirlpool: Boolean = false, val standMin: Int? = null)

fun Recipe.boilAdditions(): List<BoilAddition> {
    val boil = boilTimeMin
    val fromHops = hops
        .filter { it.usage.inBoil || it.usage == HopUsage.WHIRLPOOL }
        .map { addition(hopMinutes(it.usage, it.boilTimeMin, boil), "${it.name} ${formatAmount(it.amountG)} g", it.usage == HopUsage.WHIRLPOOL, it.boilTimeMin) }
    val fromExtras = extras
        .filter { it.usage.inBoil || it.usage == ExtraUsage.WHIRLPOOL }
        .map { addition(kettleMinutes(it.usage.inBoil, it.boilTimeMin, boil), "${it.name} ${formatAmount(it.amount)} ${it.unit.code}", it.usage == ExtraUsage.WHIRLPOOL, it.boilTimeMin) }
    val fromFermentables = fermentables
        .filter { it.usage.inBoil || it.usage == FermentableUsage.WHIRLPOOL }
        .map { addition(kettleMinutes(it.usage.inBoil, it.boilTimeMin, boil), "${it.name} ${formatAmount(it.amountKg)} kg", it.usage == FermentableUsage.WHIRLPOOL, it.boilTimeMin) }

    return (fromHops + fromExtras + fromFermentables)
        .sortedWith(compareByDescending<BoilAddition> { it.minutesBeforeEnd }.thenBy { it.what })
}

// The stand after the boil, as long as its longest whirlpool addition; null when nothing goes in the whirlpool.
fun Recipe.whirlpoolStandMin(): Int? =
    boilAdditions().filter { it.whirlpool }.takeIf { it.isNotEmpty() }?.maxOf { it.standMin ?: 0 }

private fun addition(minutes: Int, what: String, whirlpool: Boolean, time: Int?) =
    BoilAddition(minutes, what, whirlpool, if (whirlpool) (time ?: 0).coerceAtLeast(0) else null)

private fun hopMinutes(usage: HopUsage, boilTimeMin: Int?, boil: Int) = when (usage) {
    HopUsage.FIRST_WORT -> boil
    HopUsage.BOIL -> (boilTimeMin ?: boil).coerceAtMost(boil)
    else -> 0
}

private fun kettleMinutes(inBoil: Boolean, boilTimeMin: Int?, boil: Int) =
    if (inBoil) (boilTimeMin ?: boil).coerceAtMost(boil) else 0
