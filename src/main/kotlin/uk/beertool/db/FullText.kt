package uk.beertool.db

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.stringParam

class TsVectorColumnType : TextColumnType() {
    override fun sqlType() = "tsvector"
}

class TsQueryColumnType : TextColumnType() {
    override fun sqlType() = "tsquery"
}

fun Table.tsvector(name: String) = registerColumn(name, TsVectorColumnType())

fun tsQuery(term: String): ExpressionWithColumnType<String> =
    CustomFunction("to_tsquery", TsQueryColumnType(), stringLiteral(FTS_LANGUAGE), stringParam(term))

fun tsRank(document: Expression<*>, query: Expression<*>): ExpressionWithColumnType<Double> =
    CustomFunction("ts_rank", DoubleColumnType(), document, query)

fun recipeSearchDoc(recipeId: Expression<*>): ExpressionWithColumnType<String> =
    CustomFunction("recipe_search_doc", TsVectorColumnType(), recipeId)

infix fun Expression<*>.matches(query: Expression<*>): Op<Boolean> = MatchesOp(this, query)

private class MatchesOp(
    private val document: Expression<*>,
    private val query: Expression<*>,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        +document
        +" @@ "
        +query
    }
}

private const val FTS_LANGUAGE = "english"
