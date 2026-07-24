package uk.beertool.recipe

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import uk.beertool.db.tsvector

object Recipes : LongIdTable("recipes") {
    val userId = long("user_id")
    val no = integer("no")
    val name = text("name")
    val style = text("style").nullable()
    val description = text("description").nullable()
    val preBoilVolumeL = decimal("pre_boil_volume_l", 6, 2).nullable()
    val postBoilVolumeL = decimal("post_boil_volume_l", 6, 2)
    val fermenterVolumeL = decimal("fermenter_volume_l", 6, 2)
    val efficiency = decimal("efficiency", 4, 3)
    val boilTimeMin = integer("boil_time_min")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    val searchDoc = tsvector("search_doc").nullable()
}

object RecipeMashSteps : LongIdTable("recipe_mash_steps") {
    val recipeId = long("recipe_id")
    val tempC = decimal("temp_c", 4, 1)
    val timeMin = integer("time_min")
}

object RecipeFermentables : LongIdTable("recipe_fermentables") {
    val recipeId = long("recipe_id")
    val name = text("name")
    val type = text("type")
    val amountKg = decimal("amount_kg", 7, 3)
    val colourEbc = decimal("colour_ebc", 6, 2).nullable()
    val extractPercent = decimal("extract_percent", 4, 1).nullable()
    val usage = text("usage")
    val boilTimeMin = integer("boil_time_min").nullable()
}

object RecipeHops : LongIdTable("recipe_hops") {
    val recipeId = long("recipe_id")
    val name = text("name")
    val amountG = decimal("amount_g", 7, 2)
    val alphaAcid = decimal("alpha_acid", 4, 2)
    val boilTimeMin = integer("boil_time_min").nullable()
    val usage = text("usage")
}

object RecipeYeasts : LongIdTable("recipe_yeasts") {
    val recipeId = long("recipe_id")
    val name = text("name")
    val attenuation = decimal("attenuation", 4, 3)
    val usage = text("usage")
}

object RecipeExtras : LongIdTable("recipe_extras") {
    val recipeId = long("recipe_id")
    val name = text("name")
    val amount = decimal("amount", 9, 3)
    val unit = text("unit")
    val usage = text("usage")
    val boilTimeMin = integer("boil_time_min").nullable()
}
