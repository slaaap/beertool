package uk.beertool.batch

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.Recipes
import uk.beertool.recipe.lateAdditionAbv
import uk.beertool.recipe.measuredEfficiency
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class BatchSummary(
    val batch: Batch,
    val recipeName: String,
    val recipeNo: Int = 0,
)

data class BrewInfo(
    val count: Int,
    val lastBrewed: LocalDate?,
)

object BatchRepository {

    fun create(userId: Long, draft: NewBatch): Batch? = transaction {
        if (!ownsRecipe(userId, draft.recipeId)) return@transaction null
        val nextNo = nextBatchNo(draft.recipeId)
        val id = Batches.insertAndGetId {
            it.applyMeasured(userId, draft)
            it[Batches.userId] = userId
            it[no] = nextNo
            it[createdAt] = Instant.now().truncatedTo(ChronoUnit.MICROS)
        }.value
        findById(id, userId)
    }

    fun findById(id: Long, userId: Long): Batch? = transaction {
        Batches.selectAll()
            .where { (Batches.id eq id) and (Batches.userId eq userId) }
            .singleOrNull()
            ?.toBatch()
    }

    fun findByRecipeAndNo(recipeId: Long, no: Int, userId: Long): Batch? = transaction {
        Batches.selectAll()
            .where { (Batches.recipeId eq recipeId) and (Batches.no eq no) and (Batches.userId eq userId) }
            .singleOrNull()
            ?.toBatch()
    }

    private fun nextBatchNo(recipeId: Long): Int =
        (Batches.selectAll().where { Batches.recipeId eq recipeId }.maxOfOrNull { it[Batches.no] } ?: 0) + 1

    private val brewLogOrder = arrayOf(
        Batches.packagedDate.isNotNull() to SortOrder.ASC,
        Batches.brewDate to SortOrder.DESC_NULLS_LAST,
        Batches.id to SortOrder.DESC,
    )

    fun listByUser(userId: Long): List<BatchSummary> = transaction {
        val batches = Batches.selectAll()
            .where { Batches.userId eq userId }
            .orderBy(*brewLogOrder)
            .map { it.toBatch() }
        val recipes = recipeInfo(userId)
        batches.map { BatchSummary(it, recipes[it.recipeId]?.name.orEmpty(), recipes[it.recipeId]?.no ?: 0) }
    }

    fun activeBrewDay(recipeId: Long, userId: Long): Batch? = transaction {
        Batches.selectAll()
            .where {
                (Batches.recipeId eq recipeId) and (Batches.userId eq userId) and
                    Batches.packagedDate.isNull() and Batches.measuredOg.isNull()
            }
            .orderBy(Batches.id to SortOrder.DESC)
            .firstOrNull()
            ?.toBatch()
    }

    fun listByRecipe(recipeId: Long, userId: Long): List<Batch> = transaction {
        Batches.selectAll()
            .where { (Batches.recipeId eq recipeId) and (Batches.userId eq userId) }
            .orderBy(*brewLogOrder)
            .map { it.toBatch() }
    }

    fun update(id: Long, userId: Long, draft: NewBatch): Batch? = transaction {
        if (!ownsRecipe(userId, draft.recipeId)) return@transaction null
        val rows = Batches.update({ (Batches.id eq id) and (Batches.userId eq userId) }) {
            it.applyMeasured(userId, draft)
        }
        if (rows == 0) null else findById(id, userId)
    }

    fun markPackaged(id: Long, userId: Long, on: LocalDate): Batch? = transaction {
        val rows = Batches.update({ (Batches.id eq id) and (Batches.userId eq userId) }) {
            it[packagedDate] = on
        }
        if (rows == 0) null else findById(id, userId)
    }

    fun delete(id: Long, userId: Long): Boolean = transaction {
        Batches.deleteWhere { (Batches.id eq id) and (Batches.userId eq userId) } > 0
    }

    fun brewInfoByRecipe(userId: Long): Map<Long, BrewInfo> = transaction {
        Batches.selectAll()
            .where { Batches.userId eq userId }
            .map { it[Batches.recipeId] to it[Batches.brewDate] }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dates) -> BrewInfo(count = dates.size, lastBrewed = dates.filterNotNull().maxOrNull()) }
    }

    private fun UpdateBuilder<*>.applyMeasured(userId: Long, draft: NewBatch) {
        this[Batches.recipeId] = draft.recipeId
        this[Batches.brewDate] = draft.brewDate
        this[Batches.packagedDate] = draft.packagedDate
        this[Batches.measuredOg] = draft.measuredOg?.toBigDecimal()
        this[Batches.measuredFg] = draft.measuredFg?.toBigDecimal()
        this[Batches.measuredPreBoilVolumeL] = draft.measuredPreBoilVolumeL?.toBigDecimal()
        this[Batches.measuredPostBoilVolumeL] = draft.measuredPostBoilVolumeL?.toBigDecimal()
        this[Batches.measuredFermenterVolumeL] = draft.measuredFermenterVolumeL?.toBigDecimal()
        this[Batches.notes] = draft.notes
        val stamp = stamp(userId, draft)
        this[Batches.abv] = stamp.abv?.toBigDecimal()
        this[Batches.mashEfficiency] = stamp.mashEfficiency?.toBigDecimal()
    }

    private class Stamp(val abv: Double?, val mashEfficiency: Double?)

    private fun stamp(userId: Long, draft: NewBatch): Stamp {
        val recipe = RecipeRepository.findById(draft.recipeId, userId)

        val lateAbv = recipe?.let { it.lateAdditionAbv(draft.measuredFermenterVolumeL ?: it.fermenterVolumeL) } ?: 0.0
        val og = draft.measuredOg
        return Stamp(
            abv = measuredAbv(og, draft.measuredFg, lateAbv),
            mashEfficiency = if (og == null) null
            else recipe?.let { it.measuredEfficiency(og, draft.measuredPostBoilVolumeL ?: it.postBoilVolumeL) },
        )
    }

    private fun ownsRecipe(userId: Long, recipeId: Long) =
        Recipes.selectAll()
            .where { (Recipes.id eq recipeId) and (Recipes.userId eq userId) }
            .any()

    private data class RecipeRef(val name: String, val no: Int)

    private fun recipeInfo(userId: Long): Map<Long, RecipeRef> =
        Recipes.selectAll()
            .where { Recipes.userId eq userId }
            .associate { it[Recipes.id].value to RecipeRef(it[Recipes.name], it[Recipes.no]) }

    private fun ResultRow.toBatch() = Batch(
        id = this[Batches.id].value,
        recipeId = this[Batches.recipeId],
        userId = this[Batches.userId],
        no = this[Batches.no],
        brewDate = this[Batches.brewDate],
        packagedDate = this[Batches.packagedDate],
        measuredOg = this[Batches.measuredOg]?.toDouble(),
        measuredFg = this[Batches.measuredFg]?.toDouble(),
        measuredPreBoilVolumeL = this[Batches.measuredPreBoilVolumeL]?.toDouble(),
        measuredPostBoilVolumeL = this[Batches.measuredPostBoilVolumeL]?.toDouble(),
        measuredFermenterVolumeL = this[Batches.measuredFermenterVolumeL]?.toDouble(),
        notes = this[Batches.notes],
        abv = this[Batches.abv]?.toDouble(),
        mashEfficiency = this[Batches.mashEfficiency]?.toDouble(),
        createdAt = this[Batches.createdAt],
    )
}
