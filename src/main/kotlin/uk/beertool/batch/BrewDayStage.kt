package uk.beertool.batch

enum class BrewDayStage(val label: String) {
    WATER("Water"),
    MASH("Mash"),
    BOIL("Boil"),
    CHILL("Chill"),
    FERMENT("Ferment");

    val previous get() = entries.getOrNull(ordinal - 1)
    val next get() = entries.getOrNull(ordinal + 1)
}
