package uk.beertool.push

import uk.beertool.brewhouse.Phase
import uk.beertool.brewhouse.Session
import uk.beertool.recipe.BoilAddition

// The key tells one alert from another within a run, so the same one is never sent twice.
data class BrewAlert(val key: String, val title: String, val body: String)

// What the brewer must do now, read from the change between two brewhouse reports. With no earlier report, as after a
// beertool restart, nothing is announced: whatever came due meanwhile is already past.
fun brewAlerts(previous: Session?, current: Session, additions: List<BoilAddition>): List<BrewAlert> {
    if (previous == null || previous.batchId != current.batchId) return emptyList()
    val entered = current.phase != previous.phase
    return when {
        entered && current.phase == Phase.DOUGH_IN ->
            listOf(BrewAlert("dough_in", "Strike water is ready", "Add the grain, then tap Grain is in."))
        entered && current.phase == Phase.MASHED ->
            listOf(BrewAlert("mashed", "Mash done", "Lauter or lift the bag, then tap Start boil."))
        entered && current.phase == Phase.FAULT ->
            listOf(BrewAlert("fault", "Heating stopped", "${current.fault ?: "The brewhouse reported a fault"}. Fix it, then Resume or Stop."))
        else -> dueAdditions(previous, current, additions)
    }
}

// An addition is due as the boil's time left passes its minute. A boil that ran out reaches flame-out; one stopped early does not.
private fun dueAdditions(previous: Session, current: Session, additions: List<BoilAddition>): List<BrewAlert> {
    val before = previous.boilLeftMin ?: if (previous.phase == Phase.BOIL_HEAT) Double.MAX_VALUE else return emptyList()
    val now = when {
        current.phase == Phase.BOIL -> current.boilLeftMin ?: return emptyList()
        previous.phase == Phase.BOIL && current.phase == Phase.DONE && before <= 1.0 -> 0.0
        else -> return emptyList()
    }
    return additions.filter { it.minutesBeforeEnd < before && it.minutesBeforeEnd >= now }
        .groupBy { it.minutesBeforeEnd }
        .map { (minute, due) ->
            BrewAlert("boil-$minute", if (minute == 0) "Flame-out: add now" else "Add at $minute min", due.joinToString(", ") { it.what })
        }
}
