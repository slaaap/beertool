package uk.beertool.web

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import uk.beertool.recipe.AmountUnit
import uk.beertool.recipe.CodeLookup
import uk.beertool.recipe.Coded
import uk.beertool.recipe.ExtraUsage
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.FermentableUsage
import uk.beertool.recipe.HopUsage
import uk.beertool.recipe.YeastUsage
import java.time.LocalDate

private fun stringDescriptor(name: String) = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

object BlankAsNullDouble : KSerializer<Double?> {
    override val descriptor = stringDescriptor("BlankAsNullDouble")
    override fun deserialize(decoder: Decoder) = decoder.decodeString().trim().toDoubleOrNull()
    override fun serialize(encoder: Encoder, value: Double?) = encoder.encodeString(value?.toString().orEmpty())
}

object BlankAsNullInt : KSerializer<Int?> {
    override val descriptor = stringDescriptor("BlankAsNullInt")
    override fun deserialize(decoder: Decoder) = decoder.decodeString().trim().toIntOrNull()
    override fun serialize(encoder: Encoder, value: Int?) = encoder.encodeString(value?.toString().orEmpty())
}

object BlankAsNullLong : KSerializer<Long?> {
    override val descriptor = stringDescriptor("BlankAsNullLong")
    override fun deserialize(decoder: Decoder) = decoder.decodeString().trim().toLongOrNull()
    override fun serialize(encoder: Encoder, value: Long?) = encoder.encodeString(value?.toString().orEmpty())
}

object BlankAsNullDate : KSerializer<LocalDate?> {
    override val descriptor = stringDescriptor("BlankAsNullDate")

    override fun deserialize(decoder: Decoder): LocalDate? {
        val text = decoder.decodeString().trim().ifBlank { return null }
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: LocalDate?) = encoder.encodeString(value?.toString().orEmpty())
}

abstract class CodedSerializer<E>(
    private val lookup: CodeLookup<E>,
    name: String,
) : KSerializer<E> where E : Enum<E>, E : Coded {
    override val descriptor = stringDescriptor(name)
    override fun deserialize(decoder: Decoder) = lookup.fromCodeOrNull(decoder.decodeString()) ?: lookup.default
    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.code)
}

object FermentableTypeCode : CodedSerializer<FermentableType>(FermentableType, "FermentableType")
object FermentableUsageCode : CodedSerializer<FermentableUsage>(FermentableUsage, "FermentableUsage")
object HopUsageCode : CodedSerializer<HopUsage>(HopUsage, "HopUsage")
object YeastUsageCode : CodedSerializer<YeastUsage>(YeastUsage, "YeastUsage")
object ExtraUsageCode : CodedSerializer<ExtraUsage>(ExtraUsage, "ExtraUsage")
object AmountUnitCode : CodedSerializer<AmountUnit>(AmountUnit, "AmountUnit")
