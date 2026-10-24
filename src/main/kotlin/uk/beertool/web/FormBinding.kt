package uk.beertool.web

import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.decodeFromStringMap

@OptIn(ExperimentalSerializationApi::class)
suspend inline fun <reified T> ApplicationCall.receiveForm(): T {
    val form = receiveParameters().toFormMap()
    return try {
        Properties.decodeFromStringMap<T>(form)
    } catch (e: SerializationException) {
        throw InvalidInput("The form didn't come through in a shape beertool understands.")
    }
}

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
    return list to (index.toIntOrNull() ?: return null)
}

private val LIST_KEY = Regex("""([A-Za-z]+)\.(\d+)\..+""")
