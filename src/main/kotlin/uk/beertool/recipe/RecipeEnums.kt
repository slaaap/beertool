package uk.beertool.recipe

import kotlin.enums.EnumEntries

interface Coded {
    val code: String
}

interface BrewStage : Coded {

    val takesTime: Boolean

    val inBoil: Boolean
}

abstract class CodeLookup<E>(
    val all: EnumEntries<E>,
    val default: E,
) where E : Enum<E>, E : Coded {
    fun fromCode(code: String): E = all.first { it.code == code }

    fun fromCodeOrNull(code: String?): E? = all.find { it.code == code }
}

enum class FermentableType(override val code: String) : Coded {
    MALT("malt"),
    EXTRACT("extract"),
    SUGAR("sugar"),
    UNMALTED("unmalted"),
    FRUIT("fruit");

    val isMashed get() = this == MALT || this == UNMALTED

    val hasEnzymes get() = this == MALT

    val usages: List<FermentableUsage>
        get() = when (this) {

            MALT, UNMALTED -> listOf(FermentableUsage.MASH)
            EXTRACT -> listOf(FermentableUsage.BOIL)

            SUGAR -> FermentableUsage.all - FermentableUsage.MASH

            FRUIT -> FermentableUsage.all - FermentableUsage.MASH - FermentableUsage.WHIRLPOOL
        }

    val isSimpleSugar get() = this == SUGAR || this == FRUIT

    companion object : CodeLookup<FermentableType>(entries, MALT)
}

enum class FermentableUsage(override val code: String) : BrewStage {
    MASH("mash"),
    BOIL("boil"),
    WHIRLPOOL("whirlpool"),
    PRIMARY("primary"),
    SECONDARY("secondary"),
    PACKAGING("packaging");

    val isInWort get() = this == MASH || this == BOIL || this == WHIRLPOOL

    override val takesTime get() = this == BOIL

    override val inBoil get() = this == BOIL

    companion object : CodeLookup<FermentableUsage>(entries, MASH)
}

enum class YeastUsage(override val code: String) : BrewStage {
    PRIMARY("primary"),
    SECONDARY("secondary"),
    PACKAGING("packaging");

    val attenuates get() = this != PACKAGING

    override val takesTime get() = false

    override val inBoil get() = false

    companion object : CodeLookup<YeastUsage>(entries, PRIMARY)
}

enum class HopUsage(override val code: String) : BrewStage {
    MASH("mash"),
    FIRST_WORT("first_wort"),
    BOIL("boil"),
    WHIRLPOOL("whirlpool"),
    DRY_HOP("dry_hop");

    val bitters get() = inBoil

    override val takesTime get() = this == FIRST_WORT || this == BOIL || this == WHIRLPOOL

    override val inBoil get() = this == FIRST_WORT || this == BOIL

    companion object : CodeLookup<HopUsage>(entries, BOIL)
}

enum class ExtraUsage(override val code: String) : BrewStage {
    MASH("mash"),
    BOIL("boil"),
    WHIRLPOOL("whirlpool"),
    PRIMARY("primary"),
    SECONDARY("secondary"),
    PACKAGING("packaging");

    override val takesTime get() = this == BOIL || this == WHIRLPOOL

    override val inBoil get() = this == BOIL

    companion object : CodeLookup<ExtraUsage>(entries, BOIL)
}

enum class AmountUnit(override val code: String) : Coded {
    GRAM("g"),
    KILOGRAM("kg"),
    MILLILITRE("ml"),
    LITRE("l"),
    TEASPOON("tsp"),
    EACH("each");

    companion object : CodeLookup<AmountUnit>(entries, GRAM)
}
