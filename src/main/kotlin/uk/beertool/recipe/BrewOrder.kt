package uk.beertool.recipe

@JvmName("fermentablesInBrewOrder")
fun List<RecipeFermentable>.inBrewOrder(): List<RecipeFermentable> = sortedWith(
    compareBy<RecipeFermentable> { it.usage.ordinal }
        .thenBy { it.type.ordinal }
        .thenByDescending { it.amountKg }
        .thenBy { it.name },
)

@JvmName("hopsInBrewOrder")
fun List<RecipeHop>.inBrewOrder(): List<RecipeHop> = sortedWith(
    compareBy<RecipeHop> { it.usage.ordinal }
        .thenByDescending { it.boilTimeMin ?: -1 }
        .thenByDescending { it.amountG }
        .thenBy { it.name },
)

@JvmName("yeastsInBrewOrder")
fun List<RecipeYeast>.inBrewOrder(): List<RecipeYeast> = sortedWith(
    compareBy<RecipeYeast> { it.usage.ordinal }
        .thenBy { it.name },
)

@JvmName("extrasInBrewOrder")
fun List<RecipeExtra>.inBrewOrder(): List<RecipeExtra> = sortedWith(
    compareBy<RecipeExtra> { it.usage.ordinal }
        .thenByDescending { it.boilTimeMin ?: -1 }
        .thenByDescending { it.amount }
        .thenBy { it.name },
)
