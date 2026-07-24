package uk.beertool.recipe

data class BoilAddition(val minutesBeforeEnd: Int, val what: String)

fun Recipe.boilAdditions(): List<BoilAddition> {
    val boil = boilTimeMin
    val fromHops = hops
        .filter { it.usage.inBoil || it.usage == HopUsage.WHIRLPOOL }
        .map { BoilAddition(hopMinutes(it.usage, it.boilTimeMin, boil), "${it.name} ${amount(it.amountG)} g") }
    val fromExtras = extras
        .filter { it.usage.inBoil || it.usage == ExtraUsage.WHIRLPOOL }
        .map { BoilAddition(kettleMinutes(it.usage.inBoil, it.boilTimeMin, boil), "${it.name} ${amount(it.amount)} ${it.unit.code}") }
    val fromFermentables = fermentables
        .filter { it.usage.inBoil || it.usage == FermentableUsage.WHIRLPOOL }
        .map { BoilAddition(kettleMinutes(it.usage.inBoil, it.boilTimeMin, boil), "${it.name} ${amount(it.amountKg)} kg") }

    return (fromHops + fromExtras + fromFermentables)
        .sortedWith(compareByDescending<BoilAddition> { it.minutesBeforeEnd }.thenBy { it.what })
}

private fun hopMinutes(usage: HopUsage, boilTimeMin: Int?, boil: Int) = when (usage) {
    HopUsage.FIRST_WORT -> boil
    HopUsage.BOIL -> (boilTimeMin ?: boil).coerceAtMost(boil)
    else -> 0
}

private fun kettleMinutes(inBoil: Boolean, boilTimeMin: Int?, boil: Int) =
    if (inBoil) (boilTimeMin ?: boil).coerceAtMost(boil) else 0

private fun amount(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
