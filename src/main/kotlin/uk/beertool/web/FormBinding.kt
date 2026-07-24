package uk.beertool.web

import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.decodeFromStringMap

@OptIn(ExperimentalSerializationApi::class)
suspend inline fun <reified T> ApplicationCall.receiveForm(): T =
    Properties.decodeFromStringMap<T>(receiveParameters().toFormMap())

fun Parameters.toFormMap(): Map<String, String> {
    val flat = entries().associate { (key, values) -> key to values.first() }
    val ranks = flat.keys.mapNotNull(::listIndex)
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, claimed) -> claimed.distinct().sorted().withIndex().associate { (rank, i) -> i to rank } }

    return flat.mapKeys { (key, _) ->
        val (list, index) = listIndex(key) ?: return@mapKeys key
        key.replaceFirst("$list.$index.", "$list.${ranks.getValue(list).getValue(index)}.")
    }
}

private fun listIndex(key: String): Pair<String, Int>? {
    val (list, index) = LIST_KEY.matchEntire(key)?.destructured ?: return null
    return list to index.toInt()
}

private val LIST_KEY = Regex("""([A-Za-z]+)\.(\d+)\..+""")
