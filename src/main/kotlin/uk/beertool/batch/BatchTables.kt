package uk.beertool.batch

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

object Batches : LongIdTable("batches") {
    val recipeId = long("recipe_id")
    val userId = long("user_id")
    val no = integer("no")
    val brewDate = date("brew_date").nullable()
    val packagedDate = date("packaged_date").nullable()
    val measuredOg = decimal("measured_og", 5, 3).nullable()
    val measuredFg = decimal("measured_fg", 5, 3).nullable()
    val measuredPreBoilVolumeL = decimal("measured_pre_boil_volume_l", 6, 2).nullable()
    val measuredPostBoilVolumeL = decimal("measured_post_boil_volume_l", 6, 2).nullable()
    val measuredFermenterVolumeL = decimal("measured_fermenter_volume_l", 6, 2).nullable()
    val notes = text("notes").nullable()
    val abv = decimal("abv", 4, 2).nullable()
    val mashEfficiency = decimal("mash_efficiency", 4, 3).nullable()
    val createdAt = timestamp("created_at")
}
