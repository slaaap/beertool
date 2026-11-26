@file:OptIn(ExperimentalSerializationApi::class)

package uk.beertool.brewhouse

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

// Nulls are written out because the spec requires several nullable fields to be present; fields it marks non-nullable opt out.
internal val BrewhouseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object InstantIso : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = decoder.decodeString().let {
        try {
            Instant.parse(it)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("'$it' is not an ISO-8601 date-time", e)
        }
    }
}

@Serializable
data class ApiError(val error: String)

@Serializable
enum class Phase {
    @SerialName("idle") IDLE,
    @SerialName("strike") STRIKE,
    @SerialName("dough_in") DOUGH_IN,
    @SerialName("mash") MASH,
    @SerialName("mashed") MASHED,
    @SerialName("boil_heat") BOIL_HEAT,
    @SerialName("boil") BOIL,
    @SerialName("paused") PAUSED,
    @SerialName("manual") MANUAL,
    @SerialName("done") DONE,
    @SerialName("fault") FAULT,
}

@Serializable
enum class HobMode {
    @SerialName("standby") STANDBY,
    @SerialName("on") ON,
    @SerialName("power") POWER,
    @SerialName("error") ERROR,
    @SerialName("unknown") UNKNOWN,
    @SerialName("no_bus") NO_BUS,
}

@Serializable
data class Hob(
    val online: Boolean,
    val mode: HobMode? = null,
    val powerW: Int? = null,
)

@Serializable
data class Thermometer(
    val online: Boolean,
    val topC: Double? = null,
    val bottomC: Double? = null,
    val batteryPct: Int? = null,
)

@Serializable
data class Pump(
    val online: Boolean,
    val on: Boolean? = null,
    val powerW: Double? = null,
)

@Serializable
data class Devices(
    val hob: Hob,
    val thermometer: Thermometer,
    val pump: Pump,
)

@Serializable
data class Session(
    val batchId: Long? = null,
    val phase: Phase,
    val stage: String? = null,
    val step: Int? = null,
    val targetC: Double? = null,
    val tempC: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val powerW: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hobW: Int? = null,
    val stepLeftMin: Double? = null,
    val etaMin: Double? = null,
    val boilMinutes: Int? = null,
    val boilLeftMin: Double? = null,
    val fault: String? = null,
    val note: String? = null,
    @Serializable(with = InstantIso::class) val startedAt: Instant? = null,
    @Serializable(with = InstantIso::class) val updatedAt: Instant,
)

@Serializable
data class CommandResult(
    val id: String,
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class Report(
    val software: String,
    val devices: Devices,
    val ready: Boolean,
    val session: Session?,
    val commandResults: List<CommandResult>,
)

@Serializable
data class Status(
    val online: Boolean,
    @Serializable(with = InstantIso::class) val lastSeenAt: Instant?,
    val report: Report?,
)

@Serializable
enum class CommandType {
    @SerialName("start") START,
    @SerialName("dough_in") DOUGH_IN,
    @SerialName("start_boil") START_BOIL,
    @SerialName("boiling") BOILING,
    @SerialName("pause") PAUSE,
    @SerialName("resume") RESUME,
    @SerialName("stop") STOP,
}

@Serializable
data class CommandRequest(
    val type: CommandType,
    val batchId: Long? = null,
)

// The Brew Day page's own command and status shapes, not part of the spec.
@Serializable
data class PageCommandRequest(
    val type: CommandType,
    val batchId: Long? = null,
    val grainTempC: Double? = null,
    val mashThicknessLPerKg: Double? = null,
) {
    fun toCommand() = CommandRequest(type, batchId)
}

// serverNow lets the page age the report on beertool's clock rather than trusting its own or the controller's.
@Serializable
data class PageStatus(
    val online: Boolean,
    @Serializable(with = InstantIso::class) val lastSeenAt: Instant?,
    val report: Report?,
    @Serializable(with = InstantIso::class) val serverNow: Instant,
)

@Serializable
enum class CommandStatus {
    @SerialName("pending") PENDING,
    @SerialName("done") DONE,
    @SerialName("refused") REFUSED,
    @SerialName("expired") EXPIRED,
}

@Serializable
data class Command(
    val id: String,
    val type: CommandType,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val schedule: Schedule? = null,
    @Serializable(with = InstantIso::class) val createdAt: Instant,
    val status: CommandStatus,
    val error: String? = null,
)

@Serializable
data class SyncResponse(val commands: List<Command>)

@Serializable
data class ScheduleStep(val tempC: Double, val minutes: Int)

@Serializable
data class BoilPlan(val minutes: Int, val volumeL: Double)

@Serializable
data class Schedule(
    val batchId: Long,
    val recipeName: String,
    val waterL: Double,
    val grainKg: Double,
    val strikeC: Double?,
    val steps: List<ScheduleStep>,
    val boil: BoilPlan?,
)

@Serializable
data class Reading(
    @Serializable(with = InstantIso::class) val at: Instant,
    val phase: Phase,
    val tempC: Double? = null,
    val targetC: Double? = null,
    val powerW: Int? = null,
)
