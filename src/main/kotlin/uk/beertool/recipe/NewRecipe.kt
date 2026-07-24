package uk.beertool.recipe

data class NewRecipe(
    val name: String,
    val style: String? = null,
    val description: String? = null,
    val preBoilVolumeL: Double? = DEFAULT_PRE_BOIL_VOLUME_L,
    val postBoilVolumeL: Double = DEFAULT_POST_BOIL_VOLUME_L,
    val fermenterVolumeL: Double = DEFAULT_FERMENTER_VOLUME_L,
    val efficiency: Double = DEFAULT_EFFICIENCY,
    val boilTimeMin: Int = DEFAULT_BOIL_TIME_MIN,
    val mashSteps: List<MashStep> = DEFAULT_MASH_STEPS,
    val fermentables: List<RecipeFermentable> = emptyList(),
    val hops: List<RecipeHop> = emptyList(),
    val yeasts: List<RecipeYeast> = emptyList(),
    val extras: List<RecipeExtra> = emptyList(),
)
