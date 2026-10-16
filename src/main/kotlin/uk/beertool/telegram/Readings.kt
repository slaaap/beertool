package uk.beertool.telegram

import uk.beertool.batch.NewBatch

data class Readings(
    val og: Double? = null,
    val fg: Double? = null,
    val preBoilL: Double? = null,
    val postBoilL: Double? = null,
    val fermenterL: Double? = null,
    val note: String? = null,
)

sealed interface ReadingsParse {
    data class Ok(val readings: Readings) : ReadingsParse
    data class Invalid(val reason: String) : ReadingsParse
    data object NotReadings : ReadingsParse
}

private val READING = Regex(
    """\b(og|fg|pre(?:-?boil)?|post(?:-?boil)?|ferm(?:enter)?|fv)\s*[:=]?\s*(\d+(?:[.,]\d+)?)\s*(?:l\b)?""",
    RegexOption.IGNORE_CASE,
)
private val NOTE = Regex("""\bnotes?\b\s*:?\s*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SEPARATORS = Regex("""[\s,;]+""")

fun parseReadings(text: String): ReadingsParse {
    val noteMatch = NOTE.find(text)
    val note = noteMatch?.groupValues?.get(1)?.trim()?.ifBlank { null }
    val figures = noteMatch?.let { text.substring(0, it.range.first) } ?: text

    val matches = READING.findAll(figures).toList()
    if (figures.replace(READING, "").replace(SEPARATORS, "").isNotEmpty()) return ReadingsParse.NotReadings
    if (matches.isEmpty() && note == null) return ReadingsParse.NotReadings

    var readings = Readings(note = note)
    for (m in matches) {
        val key = m.groupValues[1].lowercase()
        val value = m.groupValues[2].replace(',', '.').toDouble()
        readings = when {
            key == "og" -> readings.copy(og = gravity(value) ?: return invalidGravity(key, value))
            key == "fg" -> readings.copy(fg = gravity(value) ?: return invalidGravity(key, value))
            else -> {
                if (value <= 0.0 || value > MAX_VOLUME_L) return ReadingsParse.Invalid("$value L doesn't look like a volume.")
                when {
                    key.startsWith("pre") -> readings.copy(preBoilL = value)
                    key.startsWith("post") -> readings.copy(postBoilL = value)
                    else -> readings.copy(fermenterL = value)
                }
            }
        }
    }
    return ReadingsParse.Ok(readings)
}

// Brewers write gravity both ways, as 1.052 or as 1052 gravity points.
private fun gravity(value: Double) = when (value) {
    in 0.98..1.2 -> value
    in 980.0..1200.0 -> value / 1000
    else -> null
}

private fun invalidGravity(key: String, value: Double) =
    ReadingsParse.Invalid("${key.uppercase()} $value doesn't look like a gravity. Try 1.052 or 1052.")

private const val MAX_VOLUME_L = 1000.0

fun Readings.applyTo(draft: NewBatch) = draft.copy(
    measuredOg = og ?: draft.measuredOg,
    measuredFg = fg ?: draft.measuredFg,
    measuredPreBoilVolumeL = preBoilL ?: draft.measuredPreBoilVolumeL,
    measuredPostBoilVolumeL = postBoilL ?: draft.measuredPostBoilVolumeL,
    measuredFermenterVolumeL = fermenterL ?: draft.measuredFermenterVolumeL,
    notes = note?.let { listOfNotNull(draft.notes, it).joinToString("\n") } ?: draft.notes,
)
