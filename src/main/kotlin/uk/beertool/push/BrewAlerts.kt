package uk.beertool.push

import uk.beertool.brewhouse.Phase
import uk.beertool.brewhouse.Session
import uk.beertool.common.fmt
import uk.beertool.recipe.BoilAddition
import kotlin.math.roundToInt

// The key tells one alert from another within a run, so the same one is never sent twice.
data class BrewAlert(val key: String, val title: String, val body: String)

// What the brewer must do now, read from the change between two brewhouse reports. With no earlier report, as after a
// beertool restart, nothing is announced: whatever came due meanwhile is already past.
fun brewAlerts(previous: Session?, current: Session, additions: List<BoilAddition>): List<BrewAlert> {
    if (previous == null || previous.batchId != current.batchId) return emptyList()
    val entered = current.phase != previous.phase
    val whirlpool = additions.filter { it.whirlpool }
    return when {
        entered && current.phase == Phase.DOUGH_IN ->
            listOf(BrewAlert("dough_in", "Strike water is ready", "Add the grain, then tap Grain is in."))
        entered && current.phase == Phase.MASHED ->
            listOf(BrewAlert("mashed", "Mash done", "Lauter or lift the bag, then tap Start boil."))
        entered && current.phase == Phase.FAULT ->
            listOf(BrewAlert("fault", "Heating stopped", "${current.fault ?: "The brewhouse reported a fault"}. Fix it, then Resume or Stop."))
        entered && current.phase == Phase.COOLING -> dueAdditions(previous, current, additions) +
            BrewAlert("cooling", "Boil done: start chilling", "The whirlpool additions go in at ${fmt(current.targetC, 0)} °C.")
        entered && current.phase == Phase.WHIRLPOOL -> listOf(
            BrewAlert("whirlpool", "Whirlpool: chiller off, add now", whirlpool.joinToString(", ") { it.what } + standNote(current.whirlpoolLeftMin)),
        )
        entered && current.phase == Phase.DONE && (previous.whirlpoolLeftMin ?: Double.MAX_VALUE) <= 1.0 ->
            listOf(BrewAlert("stand_done", "Whirlpool stand done", "Chill to pitching temperature."))
        else -> dueAdditions(previous, current, additions)
    }
}

private fun standNote(leftMin: Double?) = leftMin?.roundToInt()?.takeIf { it > 0 }?.let { ". Stand $it min." } ?: "."

// An addition is due as the boil's time left passes its minute. A boil that ran out reaches flame-out; one stopped early
// does not. Whirlpool additions wait for the whirlpool, not flame-out.
private fun dueAdditions(previous: Session, current: Session, additions: List<BoilAddition>): List<BrewAlert> {
    val before = previous.boilLeftMin ?: if (previous.phase == Phase.BOIL_HEAT) Double.MAX_VALUE else return emptyList()
    val ranOut = previous.phase == Phase.BOIL && (current.phase == Phase.DONE || current.phase == Phase.COOLING) && before <= 1.0
    val now = when {
        current.phase == Phase.BOIL -> current.boilLeftMin ?: return emptyList()
        ranOut -> 0.0
        else -> return emptyList()
    }
    return additions.filter { !it.whirlpool && it.minutesBeforeEnd < before && it.minutesBeforeEnd >= now }
        .groupBy { it.minutesBeforeEnd }
        .map { (minute, due) ->
            BrewAlert("boil-$minute", if (minute == 0) "Flame-out: add now" else "Add at $minute min", due.joinToString(", ") { it.what })
        }
}
