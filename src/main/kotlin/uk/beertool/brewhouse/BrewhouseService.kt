package uk.beertool.brewhouse

import uk.beertool.batch.Batch
import uk.beertool.batch.BatchRepository
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.User
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

val DEFAULT_READING_INTERVAL: Duration = Duration.ofSeconds(10)

// The one place both the controller's token routes and the Brew Day page's session routes go through.
class BrewhouseService(
    private val brewhouses: Brewhouses = Brewhouses(),
    private val readingInterval: Duration = DEFAULT_READING_INTERVAL,
    private val now: () -> Instant = Instant::now,
) {
    private val lastReadingAt = ConcurrentHashMap<Pair<Long, Long>, Instant>()

    fun sync(user: User, report: Report): SyncResponse {
        val commands = brewhouses.sync(user.id, report)
        report.session?.let { record(user, it) }
        return SyncResponse(commands)
    }

    fun status(user: User): Status = brewhouses.status(user.id)

    fun queue(user: User, request: CommandRequest): Command {
        val schedule = if (request.type == CommandType.START) {
            schedule(user, request.batchId ?: throw BrewhouseProblem(400, "A start command needs a batchId."))
        } else {
            null
        }
        return brewhouses.queue(user.id, request.type, schedule)
    }

    fun command(user: User, id: String): Command =
        brewhouses.command(user.id, id) ?: throw BrewhouseProblem(404, "No such command, or it was queued too long ago.")

    fun schedule(user: User, batchId: Long): Schedule {
        val batch = ownBatch(user, batchId)
        val recipe = RecipeRepository.findById(batch.recipeId, user.id) ?: throw noBatch()
        return brewhouseSchedule(batch.id, recipe, user.preferences)
    }

    fun readings(user: User, batchId: Long, since: Instant?): List<Reading> =
        BrewhouseReadingRepository.forBatch(ownBatch(user, batchId).id, since)

    private fun ownBatch(user: User, batchId: Long): Batch = BatchRepository.findById(batchId, user.id) ?: throw noBatch()

    private fun noBatch() = BrewhouseProblem(404, "No such batch.")

    // Keyed by user too, so a controller reporting someone else's batch id cannot hold back that brewer's own readings.
    private fun record(user: User, session: Session) {
        val batchId = session.batchId ?: return
        val at = now()
        val key = user.id to batchId
        var due = false
        lastReadingAt.compute(key) { _, last ->
            if (last == null || Duration.between(last, at) >= readingInterval) at.also { due = true } else last
        }
        if (!due) return
        if (BatchRepository.findById(batchId, user.id) == null) return
        BrewhouseReadingRepository.add(batchId, Reading(at, session.phase, session.tempC, session.targetC, session.powerW))
        lastReadingAt.values.removeIf { Duration.between(it, at) > STALE_THROTTLE }
    }

    private companion object {
        val STALE_THROTTLE: Duration = Duration.ofHours(1)
    }
}
