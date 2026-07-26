package uk.beertool.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BeerStyle(val name: String, val url: String? = null)

object BeerStyles {
    val all: List<BeerStyle> = load()

    private val byName = all.associateBy { it.name }

    fun urlFor(name: String?): String? = name?.let { byName[it]?.url }

    val nameToUrlJson: String = Json.encodeToString(all.mapNotNull { s -> s.url?.let { s.name to it } }.toMap())

    private fun load(): List<BeerStyle> {
        val text = BeerStyles::class.java.getResourceAsStream("/beer-styles.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Missing resource: beer-styles.json")
        return Json.decodeFromString(text)
    }
}
