package uk.beertool.web

import kotlinx.html.*
import uk.beertool.recipe.AmountUnit
import uk.beertool.recipe.BrewStage
import uk.beertool.recipe.CodeLookup
import uk.beertool.recipe.Coded
import uk.beertool.recipe.DEFAULT_ATTENUATION
import uk.beertool.recipe.ExtraUsage
import uk.beertool.recipe.MashStep
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeExtra
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeHop
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.RecipeYeast
import uk.beertool.recipe.YeastUsage
import uk.beertool.recipe.inBrewOrder
import uk.beertool.user.BrewerPreferences
import uk.beertool.user.User

fun HTML.recipeFormPage(user: User, recipe: Recipe?, stats: RecipeStats) {
    val editing = recipe != null
    val prefs = user.preferences
    page(if (editing) "Edit recipe" else "New recipe", user) {
        h1 { +(if (editing) "Edit recipe" else "New recipe") }

        form(action = if (recipe != null) "/recipes/${recipe.no}" else "/recipes", method = FormMethod.post) {
            id = "recipe-form"

            actionBar {
                submitInput(classes = "btn primary") { value = if (editing) "Save changes" else "Create recipe" }
                btnLink(if (recipe != null) "/recipes/${recipe.no}" else "/recipes", "Cancel", Icon.BACK)
            }

            fieldGrid(columns = 2) {
                field("Name") { textInput(name = "name") { value = recipe?.name ?: ""; required = true } }
                field("Style") {
                    textInput(name = "style") {
                        value = recipe?.style ?: ""
                        attributes["list"] = "beer-styles"
                        attributes["autocomplete"] = "off"
                    }
                    a(classes = "bjcp-link") {
                        id = "style-bjcp"; target = "_blank"; attributes["rel"] = "noopener"; hidden = true
                        +"View on BJCP ↗"
                    }
                }
            }
            dataList {
                id = "beer-styles"
                BeerStyles.all.forEach { s -> option { value = s.name } }
            }
            fieldGrid(columns = 1) {
                field("Description") { textArea { name = "description"; +(recipe?.description ?: "") } }
            }
            fieldGrid(columns = 3) {
                field("Pre-boil (L)") {
                    numberInput(name = "preBoilVolumeL") {
                        value = (recipe?.preBoilVolumeL ?: prefs.preBoilVolumeL).toString()
                        numAttrs("0.5")
                    }
                }
                field("Post-boil (L)") {
                    numberInput(name = "postBoilVolumeL") {
                        value = (recipe?.postBoilVolumeL ?: prefs.postBoilVolumeL).toString()
                        numAttrs("0.5")
                    }
                }
                field("Fermenter (L)") {
                    numberInput(name = "fermenterVolumeL") {
                        value = (recipe?.fermenterVolumeL ?: prefs.fermenterVolumeL).toString()
                        numAttrs("0.5")
                    }
                }
            }
            fieldGrid(columns = 2) {
                field("Efficiency (%)") {
                    numberInput(name = "efficiency") {
                        value = fmt((recipe?.efficiency ?: prefs.efficiency).asPercent(), 0)
                        numAttrs("1", max = "100")
                    }
                }
                field("Boil (min)") {
                    numberInput(name = "boilTimeMin") {
                        value = (recipe?.boilTimeMin ?: prefs.boilTimeMin).toString(); numAttrs("1")
                    }
                }
            }

            editableBill(MASH, "Mash", "Add rest", TR::mashHead, "bill mash") {
                (recipe?.mashSteps ?: prefs.mashSteps).forEachIndexed { i, m -> mashStepRow(i, m) }
            }
            fermentableSections(recipe)
            hopSections(recipe, prefs, recipe?.boilTimeMin ?: prefs.boilTimeMin)
            editableBill(YEASTS, "Yeasts", "Add yeast", TR::yeastHead) {
                recipe?.yeasts.orEmpty().inBrewOrder().forEachIndexed { i, y -> yeastRow(i, y) }
            }
            editableBill(EXTRAS, "Extras", "Add extra", TR::extraHead) {
                recipe?.extras.orEmpty().inBrewOrder().forEachIndexed { i, e -> extraRow(i, e) }
            }

            sectionHead("Estimates")
            div { id = "stats"; div("stats") { statsTiles(stats) } }
        }

        prototypeRow(MASH) { mashStepRow(0, null) }
        FERMENTABLE_SECTIONS.forEach { section -> prototypeRow(section.key) { fermentableRow(0, null, section) } }
        HOP_SECTIONS.forEach { section -> prototypeRow(section.key) { hopRow(0, null, section) } }
        prototypeRow(YEASTS) { yeastRow(0, null) }
        prototypeRow(EXTRAS) { extraRow(0, null) }

        js("recipe-form.js")
        script(type = "application/json") { id = "bjcp-map"; unsafe { +BeerStyles.nameToUrlJson } }
        js("style-link.js")
    }
}

private const val MASH = "mash"
private const val FERMENTABLES = "ferm"
private const val YEASTS = "yeast"
private const val EXTRAS = "extra"

private fun FlowContent.editableBill(
    key: String,
    heading: String,
    addLabel: String,
    header: TR.() -> Unit,
    tableClass: String = "bill",
    rows: TBODY.() -> Unit,
) {
    sectionHead(heading) {
        button(type = ButtonType.button, classes = "btn small") {
            onClick = "addRow('$key-body','#$key-proto tr')"
            span("ico") { unsafe { +Icon.PLUS.markup() } }
            span { +addLabel }
        }
    }
    scrollTable(tableClass) {
        thead { tr { header(); actionSlot() } }
        tbody { id = "$key-body"; rows() }
    }
}

private fun FlowContent.prototypeRow(key: String, row: TBODY.() -> Unit) =
    table { style = "display:none"; tbody { id = "$key-proto"; row() } }

private fun rowField(list: String, index: Int, field: String) = "$list.$index.$field"

private fun TD.removeButton() = rowRemoveButton("removeRow(this)", "Remove row")

private fun TBODY.mashStepRow(i: Int, m: MashStep?) {
    fun field(name: String) = rowField("mashSteps", i, name)
    tr {
        stepNumberCell()
        numInput(field("tempC"), m?.tempC?.toString(), "0.5")
        emptyCell()
        emptyCell()
        numInput(field("timeMin"), m?.timeMin?.toString(), "1")
        emptyCell()
        td("right") { removeButton() }
    }
}

private fun FlowContent.fermentableSections(recipe: Recipe?) {
    val bill = recipe?.fermentables.orEmpty().inBrewOrder()
    sectionHead("Fermentables") {
        FERMENTABLE_SECTIONS.filter { it.primary }.forEach { addStageButton(it.key, it.label) }
        otherStages(FERMENTABLE_SECTIONS.filterNot { it.primary }.map { it.key to it.label })
    }
    stages("ferm-sections") {
        var index = 0
        FERMENTABLE_SECTIONS.forEach { section ->
            val rows = bill.filter { it.usage == section.usage }
            stage(section.key, rows.isEmpty(), section.usage.inBoil) {
                stageTitle(section.label)
                scrollTable("bill ferm") {
                    thead { tr { fermentableStageColumns(section); actionSlot() } }
                    tbody {
                        id = section.bodyId
                        rows.forEachIndexed { i, f -> fermentableRow(index + i, f, section) }
                    }
                }
            }
            index += rows.size
        }
    }
}

private fun TBODY.fermentableRow(i: Int, f: RecipeFermentable?, section: FermentableSection) {
    fun field(name: String) = rowField("fermentables", i, name)
    tr {
        td {
            textInput(name = field("name")) { value = f?.name ?: "" }
            hiddenInput(name = field("usage")) { value = section.usage.code }
        }
        numInput(field("amountKg"), f?.amountKg?.toString(), "0.01")
        td {
            select {
                name = field("type")
                val chosen = f?.type ?: section.types.first()
                section.types.forEach { type ->
                    option {
                        value = type.code
                        if (type == chosen) selected = true
                        +prettify(type.name)
                    }
                }
            }
        }
        if (section.usage.takesTime) numInput(field("boilTimeMin"), f?.boilTimeMin?.toString(), "1")
        numInput(field("colourEbc"), f?.colourEbc?.toString(), "1")
        numInput(field("extractPercent"), f?.extractPercent?.toString(), "0.1", placeholder = "auto")
        td("right") { removeButton() }
    }
}

private fun FlowContent.hopSections(recipe: Recipe?, prefs: BrewerPreferences, boilTimeMin: Int) {
    val hops = recipe?.hops.orEmpty().inBrewOrder()
    sectionHead("Hops") {
        HOP_SECTIONS.filter { it.primary }.forEach { addStageButton(it.key, prettify(it.usage.name)) }
        otherStages(HOP_SECTIONS.filterNot { it.primary }.map { it.key to prettify(it.usage.name) })
    }
    stages("hop-sections") {
        var index = 0
        HOP_SECTIONS.forEach { section ->
            val rows = hops.filter { it.usage == section.usage }
            stage(section.key, rows.isEmpty(), section.usage.inBoil) {
                stageTitle(prettify(section.usage.name), section.note(prefs, boilTimeMin))
                scrollTable("bill hops") {
                    thead { tr { hopStageColumns(section); actionSlot() } }
                    tbody {
                        id = section.bodyId
                        rows.forEachIndexed { i, h -> hopRow(index + i, h, section) }
                    }
                }
            }
            index += rows.size
        }
    }
}

private fun FlowContent.stages(id: String, block: DIV.() -> Unit) = div("stages") { this.id = id; block() }

private fun FlowContent.stage(key: String, empty: Boolean, inBoil: Boolean, block: DIV.() -> Unit) {
    div("stage") {
        id = key
        hidden = empty
        attributes["data-in-boil"] = if (inBoil) "1" else "0"
        block()
    }
}

private fun FlowContent.otherStages(stages: List<Pair<String, String>>) {
    details(classes = "more-stages") {
        summary(classes = "btn small") { +"Other" }
        div { stages.forEach { (key, label) -> addStageButton(key, label) } }
    }
}

private fun FlowContent.addStageButton(key: String, label: String) {
    button(type = ButtonType.button, classes = "btn small") {
        onClick = "addStageRow('$key')"
        span("ico") { unsafe { +Icon.PLUS.markup() } }
        span { +label }
    }
}

private fun TBODY.hopRow(i: Int, h: RecipeHop?, section: HopSection) {
    fun field(name: String) = rowField("hops", i, name)
    tr {
        td {
            textInput(name = field("name")) { value = h?.name ?: "" }
            hiddenInput(name = field("usage")) { value = section.usage.code }
        }
        numInput(field("amountG"), h?.amountG?.toString(), "1")
        td("num") {
            section.timeField?.let { stageTimeInput(field(it), h?.let(section::timeOf)) }
        }
        numInput(field("alphaAcid"), h?.alphaAcid?.toString(), "0.1")
        td("right") { removeButton() }
    }
}

private fun TD.stageTimeInput(name: String, value: Int?) =
    numberInput(name = name) {
        this.value = value?.toString() ?: ""
        numAttrs("1")
    }

private fun TBODY.yeastRow(i: Int, y: RecipeYeast?) {
    fun field(name: String) = rowField("yeasts", i, name)
    tr {
        td { textInput(name = field("name")) { value = y?.name ?: "" } }
        emptyCell()
        emptyCell()
        td { select { name = field("usage"); enumOptions(y?.usage, YeastUsage) } }
        emptyCell()
        numInput(field("attenuation"), fmt((y?.attenuation ?: DEFAULT_ATTENUATION).asPercent(), 0), "1", max = "100")
        td("right") { removeButton() }
    }
}

private fun TBODY.extraRow(i: Int, e: RecipeExtra?) {
    fun field(name: String) = rowField("extras", i, name)
    tr {
        td { textInput(name = field("name")) { value = e?.name ?: "" } }
        numInput(field("amount"), e?.amount?.toString(), "0.1")
        td { select { name = field("unit"); enumOptions(e?.unit, AmountUnit) { it.code } } }
        td { select { name = field("usage"); enumOptions(e?.usage, ExtraUsage) { u -> usageAttrs(u) } } }
        numInput(field("boilTimeMin"), e?.boilTimeMin?.toString(), "1")
        emptyCell()
        td("right") { removeButton() }
    }
}

private fun <E> SELECT.enumOptions(
    current: E?,
    lookup: CodeLookup<E>,
    label: (E) -> String = { prettify(it.name) },
    attrs: (OPTION.(E) -> Unit)? = null,
) where E : Enum<E>, E : Coded {
    val chosen = current ?: lookup.default
    lookup.all.forEach { entry ->
        option {
            value = entry.code
            if (entry == chosen) selected = true
            attrs?.invoke(this, entry)
            +label(entry)
        }
    }
}

private fun OPTION.usageAttrs(usage: BrewStage) {
    attributes["data-time"] = if (usage.takesTime) "1" else "0"
    attributes["data-in-boil"] = if (usage.inBoil) "1" else "0"
}
