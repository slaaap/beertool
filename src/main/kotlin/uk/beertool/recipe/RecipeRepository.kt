package uk.beertool.recipe

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.beertool.db.matches
import uk.beertool.db.recipeSearchDoc
import uk.beertool.db.tsQuery
import uk.beertool.db.tsRank
import java.time.Instant
import java.time.temporal.ChronoUnit

data class RecipeSummary(
    val id: Long,
    val no: Int,
    val name: String,
    val style: String?,
    val fermenterVolumeL: Double,
    val stats: RecipeStats,
)

private val UNSEARCHABLE = """[^\p{L}\p{N}-]""".toRegex()

object RecipeRepository {

    fun create(userId: Long, draft: NewRecipe): Recipe = transaction {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val nextNo = nextRecipeNo(userId)
        val recipeId = Recipes.insertAndGetId {
            it[Recipes.userId] = userId
            it[no] = nextNo
            it[name] = draft.name
            it[style] = draft.style
            it[description] = draft.description
            it[preBoilVolumeL] = draft.preBoilVolumeL?.toBigDecimal()
            it[postBoilVolumeL] = draft.postBoilVolumeL.toBigDecimal()
            it[fermenterVolumeL] = draft.fermenterVolumeL.toBigDecimal()
            it[efficiency] = draft.efficiency.toBigDecimal()
            it[boilTimeMin] = draft.boilTimeMin
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        insertLines(recipeId, draft)
        refreshSearchDoc(recipeId)
        loadById(recipeId)!!
    }

    fun findById(id: Long, userId: Long): Recipe? = transaction {
        Recipes.selectAll()
            .where { (Recipes.id eq id) and (Recipes.userId eq userId) }
            .singleOrNull()
            ?.let { loadFrom(it) }
    }

    fun findByNo(no: Int, userId: Long): Recipe? = transaction {
        Recipes.selectAll()
            .where { (Recipes.no eq no) and (Recipes.userId eq userId) }
            .singleOrNull()
            ?.let { loadFrom(it) }
    }

    private fun nextRecipeNo(userId: Long): Int =
        (Recipes.selectAll().where { Recipes.userId eq userId }.maxOfOrNull { it[Recipes.no] } ?: 0) + 1

    fun listByUser(userId: Long): List<RecipeSummary> = transaction {
        val rows = Recipes.selectAll()
            .where { Recipes.userId eq userId }
            .orderBy(Recipes.createdAt to SortOrder.DESC)
            .toList()
        val lines = linesFor(rows.map { it[Recipes.id].value })
        rows.map { toRecipe(it, lines).toSummary() }
    }

    fun search(userId: Long, term: String): List<RecipeSummary> {
        val query = tsQuery(toTsQuery(term) ?: return listByUser(userId))
        return transaction {
            val ids = Recipes.select(Recipes.id)
                .where { (Recipes.userId eq userId) and (Recipes.searchDoc matches query) }
                .orderBy(tsRank(Recipes.searchDoc, query) to SortOrder.DESC)
                .orderBy(Recipes.createdAt to SortOrder.DESC)
                .map { it[Recipes.id].value }
            summariesFor(ids)
        }
    }

    fun update(id: Long, userId: Long, draft: NewRecipe): Recipe? = transaction {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val rows = Recipes.update({ (Recipes.id eq id) and (Recipes.userId eq userId) }) {
            it[name] = draft.name
            it[style] = draft.style
            it[description] = draft.description
            it[preBoilVolumeL] = draft.preBoilVolumeL?.toBigDecimal()
            it[postBoilVolumeL] = draft.postBoilVolumeL.toBigDecimal()
            it[fermenterVolumeL] = draft.fermenterVolumeL.toBigDecimal()
            it[efficiency] = draft.efficiency.toBigDecimal()
            it[boilTimeMin] = draft.boilTimeMin
            it[updatedAt] = now
        }
        if (rows == 0) return@transaction null
        deleteLines(id)
        insertLines(id, draft)
        refreshSearchDoc(id)
        loadById(id)
    }

    fun delete(id: Long, userId: Long): Boolean = transaction {
        Recipes.deleteWhere { (Recipes.id eq id) and (Recipes.userId eq userId) } > 0
    }

    private fun toTsQuery(term: String): String? =
        term.replace(UNSEARCHABLE, " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" & ") { "$it:*" }
            .ifBlank { null }

    private fun summariesFor(ids: List<Long>): List<RecipeSummary> {
        if (ids.isEmpty()) return emptyList()
        val lines = linesFor(ids)
        val byId = Recipes.selectAll()
            .where { Recipes.id inList ids }
            .associate { it[Recipes.id].value to toRecipe(it, lines) }
        return ids.mapNotNull { byId[it]?.toSummary() }
    }

    private fun refreshSearchDoc(recipeId: Long) {
        Recipes.update({ Recipes.id eq recipeId }) {
            it[searchDoc] = recipeSearchDoc(Recipes.id)
        }
    }

    private fun Recipe.toSummary() = RecipeSummary(
        id = id,
        no = no,
        name = name,
        style = style,
        fermenterVolumeL = fermenterVolumeL,
        stats = stats(),
    )

    private fun insertLines(recipeId: Long, draft: NewRecipe) {
        draft.mashSteps.forEach { m ->
            RecipeMashSteps.insert {
                it[RecipeMashSteps.recipeId] = recipeId
                it[tempC] = m.tempC.toBigDecimal()
                it[timeMin] = m.timeMin
            }
        }
        draft.fermentables.forEach { f ->
            RecipeFermentables.insert {
                it[RecipeFermentables.recipeId] = recipeId
                it[name] = f.name
                it[type] = f.type.code
                it[amountKg] = f.amountKg.toBigDecimal()
                it[colourEbc] = f.colourEbc?.toBigDecimal()
                it[extractPercent] = f.extractPercent?.toBigDecimal()
                it[usage] = f.usage.code
                it[boilTimeMin] = f.boilTimeMin
            }
        }
        draft.hops.forEach { h ->
            RecipeHops.insert {
                it[RecipeHops.recipeId] = recipeId
                it[name] = h.name
                it[amountG] = h.amountG.toBigDecimal()
                it[alphaAcid] = h.alphaAcid.toBigDecimal()
                it[boilTimeMin] = h.boilTimeMin
                it[usage] = h.usage.code
            }
        }
        draft.yeasts.forEach { y ->
            RecipeYeasts.insert {
                it[RecipeYeasts.recipeId] = recipeId
                it[name] = y.name
                it[attenuation] = y.attenuation.toBigDecimal()
                it[usage] = y.usage.code
            }
        }
        draft.extras.forEach { e ->
            RecipeExtras.insert {
                it[RecipeExtras.recipeId] = recipeId
                it[name] = e.name
                it[amount] = e.amount.toBigDecimal()
                it[unit] = e.unit.code
                it[usage] = e.usage.code
                it[boilTimeMin] = e.boilTimeMin
            }
        }
    }

    private fun deleteLines(recipeId: Long) {
        RecipeMashSteps.deleteWhere { RecipeMashSteps.recipeId eq recipeId }
        RecipeFermentables.deleteWhere { RecipeFermentables.recipeId eq recipeId }
        RecipeHops.deleteWhere { RecipeHops.recipeId eq recipeId }
        RecipeYeasts.deleteWhere { RecipeYeasts.recipeId eq recipeId }
        RecipeExtras.deleteWhere { RecipeExtras.recipeId eq recipeId }
    }

    private fun loadById(id: Long): Recipe? =
        Recipes.selectAll().where { Recipes.id eq id }.singleOrNull()?.let { loadFrom(it) }

    private fun loadFrom(row: ResultRow): Recipe =
        toRecipe(row, linesFor(listOf(row[Recipes.id].value)))

    private fun linesFor(recipeIds: List<Long>): Lines {
        if (recipeIds.isEmpty()) return Lines()
        return Lines(
            mashSteps = RecipeMashSteps.selectAll()
                .where { RecipeMashSteps.recipeId inList recipeIds }
                .orderBy(RecipeMashSteps.id to SortOrder.ASC)
                .groupBy({ it[RecipeMashSteps.recipeId] }, { it.toMashStep() }),
            fermentables = RecipeFermentables.selectAll()
                .where { RecipeFermentables.recipeId inList recipeIds }
                .orderBy(RecipeFermentables.id to SortOrder.ASC)
                .groupBy({ it[RecipeFermentables.recipeId] }, { it.toFermentable() }),
            hops = RecipeHops.selectAll()
                .where { RecipeHops.recipeId inList recipeIds }
                .orderBy(RecipeHops.id to SortOrder.ASC)
                .groupBy({ it[RecipeHops.recipeId] }, { it.toHop() }),
            yeasts = RecipeYeasts.selectAll()
                .where { RecipeYeasts.recipeId inList recipeIds }
                .orderBy(RecipeYeasts.id to SortOrder.ASC)
                .groupBy({ it[RecipeYeasts.recipeId] }, { it.toYeast() }),
            extras = RecipeExtras.selectAll()
                .where { RecipeExtras.recipeId inList recipeIds }
                .orderBy(RecipeExtras.id to SortOrder.ASC)
                .groupBy({ it[RecipeExtras.recipeId] }, { it.toExtra() }),
        )
    }

    private class Lines(
        val mashSteps: Map<Long, List<MashStep>> = emptyMap(),
        val fermentables: Map<Long, List<RecipeFermentable>> = emptyMap(),
        val hops: Map<Long, List<RecipeHop>> = emptyMap(),
        val yeasts: Map<Long, List<RecipeYeast>> = emptyMap(),
        val extras: Map<Long, List<RecipeExtra>> = emptyMap(),
    )

    private fun toRecipe(row: ResultRow, lines: Lines): Recipe {
        val id = row[Recipes.id].value
        return Recipe(
            id = id,
            userId = row[Recipes.userId],
            no = row[Recipes.no],
            name = row[Recipes.name],
            style = row[Recipes.style],
            description = row[Recipes.description],
            preBoilVolumeL = row[Recipes.preBoilVolumeL]?.toDouble(),
            postBoilVolumeL = row[Recipes.postBoilVolumeL].toDouble(),
            fermenterVolumeL = row[Recipes.fermenterVolumeL].toDouble(),
            efficiency = row[Recipes.efficiency].toDouble(),
            boilTimeMin = row[Recipes.boilTimeMin],
            mashSteps = lines.mashSteps[id].orEmpty(),
            fermentables = lines.fermentables[id].orEmpty(),
            hops = lines.hops[id].orEmpty(),
            yeasts = lines.yeasts[id].orEmpty(),
            extras = lines.extras[id].orEmpty(),
            createdAt = row[Recipes.createdAt],
            updatedAt = row[Recipes.updatedAt],
        )
    }

    private fun ResultRow.toMashStep() = MashStep(
        tempC = this[RecipeMashSteps.tempC].toDouble(),
        timeMin = this[RecipeMashSteps.timeMin],
    )

    private fun ResultRow.toFermentable() = RecipeFermentable(
        name = this[RecipeFermentables.name],
        type = FermentableType.fromCode(this[RecipeFermentables.type]),
        amountKg = this[RecipeFermentables.amountKg].toDouble(),
        colourEbc = this[RecipeFermentables.colourEbc]?.toDouble(),
        extractPercent = this[RecipeFermentables.extractPercent]?.toDouble(),
        usage = FermentableUsage.fromCode(this[RecipeFermentables.usage]),
        boilTimeMin = this[RecipeFermentables.boilTimeMin],
    )

    private fun ResultRow.toHop() = RecipeHop(
        name = this[RecipeHops.name],
        amountG = this[RecipeHops.amountG].toDouble(),
        alphaAcid = this[RecipeHops.alphaAcid].toDouble(),
        boilTimeMin = this[RecipeHops.boilTimeMin],
        usage = HopUsage.fromCode(this[RecipeHops.usage]),
    )

    private fun ResultRow.toYeast() = RecipeYeast(
        name = this[RecipeYeasts.name],
        attenuation = this[RecipeYeasts.attenuation].toDouble(),
        usage = YeastUsage.fromCode(this[RecipeYeasts.usage]),
    )

    private fun ResultRow.toExtra() = RecipeExtra(
        name = this[RecipeExtras.name],
        amount = this[RecipeExtras.amount].toDouble(),
        unit = AmountUnit.fromCode(this[RecipeExtras.unit]),
        usage = ExtraUsage.fromCode(this[RecipeExtras.usage]),
        boilTimeMin = this[RecipeExtras.boilTimeMin],
    )
}
