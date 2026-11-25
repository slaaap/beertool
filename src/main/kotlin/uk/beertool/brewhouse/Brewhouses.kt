package uk.beertool.brewhouse

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val ONLINE_WINDOW: Duration = Duration.ofSeconds(15)
val COMMAND_TTL: Duration = Duration.ofSeconds(60)
val COMMAND_RETENTION: Duration = Duration.ofMinutes(10)

// A client looping on POST /commands could otherwise grow a user's history without bound inside the retention window.
private const val MAX_COMMANDS_PER_USER = 200

class BrewhouseProblem(val status: Int, message: String) : RuntimeException(message)

// Live state lives in memory: there is one app instance, the controller re-reports every couple of seconds and commands expire in a minute.
class Brewhouses(private val now: () -> Instant = Instant::now) {

    private class Entry {
        var report: Report? = null
        var lastSeenAt: Instant? = null
        val commands = LinkedHashMap<String, Command>()
    }

    private val entries = ConcurrentHashMap<Long, Entry>()

    private fun entry(userId: Long) = entries.computeIfAbsent(userId) { Entry() }

    fun sync(userId: Long, report: Report): List<Command> {
        val at = now()
        val entry = entry(userId)
        synchronized(entry) {
            entry.report = report
            entry.lastSeenAt = at
            report.commandResults.forEach { result ->
                entry.commands[result.id]?.takeIf { it.status != CommandStatus.DONE && it.status != CommandStatus.REFUSED }?.let {
                    entry.commands[it.id] = it.copy(
                        status = if (result.ok) CommandStatus.DONE else CommandStatus.REFUSED,
                        error = if (result.ok) null else result.error ?: "Refused without a reason.",
                    )
                }
            }
            entry.tidy(at)
            return entry.commands.values.filter { it.status == CommandStatus.PENDING }.sortedBy { it.createdAt }
        }
    }

    fun status(userId: Long): Status {
        val entry = entries[userId] ?: return Status(online = false, lastSeenAt = null, report = null)
        synchronized(entry) {
            return Status(online = entry.isOnline(now()), lastSeenAt = entry.lastSeenAt, report = entry.report)
        }
    }

    fun queue(userId: Long, type: CommandType, schedule: Schedule? = null): Command {
        val at = now()
        val entry = entry(userId)
        synchronized(entry) {
            if (!entry.isOnline(at)) throw BrewhouseProblem(409, "The brewhouse is offline.")
            if (type == CommandType.START && entry.report?.ready != true) {
                throw BrewhouseProblem(409, "The brewhouse is not ready to start: a device is offline or a session is running.")
            }
            entry.tidy(at)
            val command = Command(UUID.randomUUID().toString(), type, schedule, at, CommandStatus.PENDING)
            entry.commands[command.id] = command
            while (entry.commands.size > MAX_COMMANDS_PER_USER) entry.commands.remove(entry.commands.keys.first())
            return command
        }
    }

    fun command(userId: Long, id: String): Command? {
        val entry = entries[userId] ?: return null
        synchronized(entry) {
            entry.tidy(now())
            return entry.commands[id]
        }
    }

    private fun Entry.isOnline(at: Instant) = lastSeenAt?.let { Duration.between(it, at) < ONLINE_WINDOW } == true

    private fun Entry.tidy(at: Instant) {
        commands.values.removeIf { it.createdAt.plus(COMMAND_RETENTION) < at && it.status != CommandStatus.PENDING }
        commands.replaceAll { _, c ->
            if (c.status == CommandStatus.PENDING && Duration.between(c.createdAt, at) >= COMMAND_TTL) c.copy(status = CommandStatus.EXPIRED) else c
        }
    }
}
