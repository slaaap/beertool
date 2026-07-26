package uk.beertool.web

import kotlinx.html.*
import uk.beertool.batch.Batch
import uk.beertool.batch.BatchStats
import uk.beertool.batch.BatchSummary
import uk.beertool.batch.stats
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.user.User
import java.time.LocalDate

internal fun FlowContent.batchTable(brews: List<BatchSummary>, showRecipe: Boolean) {
    scrollTable {
        thead {
            tr {
                th(classes = if (showRecipe) null else "col-grow") { +"Brewed" }
                if (showRecipe) th(classes = "col-grow") { +"Recipe" }
                th(classes = "hide-sm") { +"Packaged" }
                th(classes = "num") { +"OG" }
                th(classes = "num") { +"FG" }
                th(classes = "num") { +"ABV" }
            }
        }
        tbody {
            brews.forEach { brew ->
                val b = brew.batch
                tr {
                    td(if (showRecipe) "tnum" else "tnum col-grow") { a(href = "/recipes/${brew.recipeNo}/batches/${b.no}", classes = "row-name") { +fmt(b.brewDate) } }
                    if (showRecipe) td("col-grow") {
                        +brew.recipeName
                        span("muted") { +" #${b.no}" }
                    }
                    td("tnum hide-sm") { +fmt(b.packagedDate) }
                    numCell(b.measuredOg, 3)
                    numCell(b.measuredFg, 3)
                    numCell(b.stats().abv, 1)
                }
            }
        }
    }
}

fun HTML.batchLogPage(user: User, brews: Page<BatchSummary>) {
    page("Brew log", user) {
        h1 { +"Brew log" }
        if (brews.total == 0) {
            div("empty") { +"Nothing brewed yet — open a recipe and log a brew." }
            actionBar { btnLink("/recipes", "Go to recipes", Icon.BACK) }
        } else {
            batchTable(brews.items, showRecipe = true)
            pager(brews) { p -> "/batches?page=$p" }
        }
    }
}

fun HTML.batchViewPage(user: User, batch: Batch, recipe: Recipe, estimate: RecipeStats) {
    val measured = batch.stats()
    val base = "/recipes/${recipe.no}/batches/${batch.no}"
    page("Brew #${batch.no} of ${recipe.name}", user) {
        h1 {
            +recipe.name
            span("title-num") { +" #${batch.no}" }
        }

        p("lede") {
            span("nowrap") { +"Brewed ${fmt(batch.brewDate)}" }
            span("nowrap") { +" · packaged ${fmt(batch.packagedDate)}" }
        }
        actionBar {
            if (user.canWrite) {
                val live = batch.packagedDate == null
                val onBrewDay = live && batch.measuredOg == null
                if (onBrewDay) {
                    btnLink("$base/brew", "Brew day", Icon.BREW, primary = true)
                } else {
                    btnLink("$base/edit", "Edit brew", Icon.EDIT, primary = true)
                }
                if (live && !onBrewDay) btnPost("$base/packaged", "Packaged today", Icon.PACKAGE)
            }
            btnLink("/recipes/${recipe.no}", "Recipe", Icon.BACK)
            if (user.canWrite) deleteButton("$base/delete", "Delete this brew?")
        }

        sectionHead("Measured")
        div("stats") { measuredTiles(measured, batch) }

        sectionHead("Volumes")
        volumesTable(recipe, batch)

        sectionHead("Recipe estimate")
        div("stats") { statsTiles(estimate) }
        p("muted") { +"Assumed efficiency ${fmt(recipe.efficiency.asPercent(), 0)}%." }

        batch.notes?.takeIf { it.isNotBlank() }?.let {
            sectionHead("Notes")
            p("notes") { +it }
        }
    }
}

private fun FlowContent.volumesTable(recipe: Recipe, batch: Batch) {
    scrollTable {
        thead { tr { th { +"Volume" }; th(classes = "num") { +"Planned (L)" }; th(classes = "num") { +"Actual (L)" } } }
        tbody {
            fun row(name: String, planned: Double?, actual: Double?) = tr {
                td { +name }; numCell(planned, 1); numCell(actual, 1)
            }
            row("Pre-boil", recipe.preBoilVolumeL, batch.measuredPreBoilVolumeL)
            row("Post-boil", recipe.postBoilVolumeL, batch.measuredPostBoilVolumeL)
            row("Fermenter", recipe.fermenterVolumeL, batch.measuredFermenterVolumeL)
        }
    }
}

private fun DIV.measuredTiles(measured: BatchStats, batch: Batch) {
    statTile("OG", fmt(batch.measuredOg, 3), null)
    statTile("FG", fmt(batch.measuredFg, 3), null)
    statTile("ABV", fmt(measured.abv, 1), "%", accent = true)
    statTile("Attenuation", fmt(measured.attenuation?.asPercent(), 0), "%")

    statTile("Efficiency", fmt(batch.mashEfficiency?.asPercent(), 0), "%", last = true)
}

fun HTML.batchFormPage(user: User, recipe: Recipe, batch: Batch?, packageToday: Boolean = false) {
    val editing = batch != null
    page(if (editing) "Edit brew" else "Log a brew", user) {
        h1 { +(if (editing) "Edit brew" else "Log a brew") }
        p("lede") { +"Recipe: "; a(href = "/recipes/${recipe.no}") { +recipe.name } }

        if (packageToday) div("notice") { +"Enter the final gravity to finish packaging." }

        val action = if (batch != null) "/recipes/${recipe.no}/batches/${batch.no}" else "/recipes/${recipe.no}/batches"
        form(action = action, method = FormMethod.post) {
            fieldGrid(columns = 2) {
                field("Brew date") {
                    dateInput(name = "brewDate") {
                        value = batch?.brewDate?.toString() ?: LocalDate.now().toString()
                    }
                }
                field("Packaged") {
                    dateInput(name = "packagedDate") {
                        value = batch?.packagedDate?.toString() ?: (if (packageToday) LocalDate.now().toString() else "")
                    }
                }
            }
            fieldGrid(columns = 2) {
                field("Measured OG") {
                    numberInput(name = "measuredOg") { value = batch?.measuredOg?.toString() ?: ""; numAttrs("0.001") }
                }
                field("Measured FG") {
                    numberInput(name = "measuredFg") {
                        value = batch?.measuredFg?.toString() ?: ""; numAttrs("0.001")
                        if (packageToday) attributes["autofocus"] = "autofocus"
                    }
                }
            }

            fieldGrid(columns = 3) {
                field("Pre-boil (L)") {
                    numberInput(name = "measuredPreBoilVolumeL") {
                        value = batch?.measuredPreBoilVolumeL?.toString() ?: ""
                        placeholder = recipe.preBoilVolumeL?.let { fmt(it, 1) } ?: ""
                        numAttrs("0.5")
                    }
                }
                field("Post-boil (L)") {
                    numberInput(name = "measuredPostBoilVolumeL") {
                        value = batch?.measuredPostBoilVolumeL?.toString() ?: ""
                        placeholder = fmt(recipe.postBoilVolumeL, 1); numAttrs("0.5")
                    }
                }
                field("Fermenter (L)") {
                    numberInput(name = "measuredFermenterVolumeL") {
                        value = batch?.measuredFermenterVolumeL?.toString() ?: ""
                        placeholder = fmt(recipe.fermenterVolumeL, 1); numAttrs("0.5")
                    }
                }
            }
            fieldGrid(columns = 1) {
                field("Notes") { textArea { name = "notes"; +(batch?.notes ?: "") } }
            }

            actionBar {
                submitInput { value = if (editing) "Save changes" else "Log brew" }
                btnLink(
                    if (batch != null) "/recipes/${recipe.no}/batches/${batch.no}" else "/recipes/${recipe.no}",
                    "Cancel", Icon.BACK,
                )
            }
        }
    }
}
