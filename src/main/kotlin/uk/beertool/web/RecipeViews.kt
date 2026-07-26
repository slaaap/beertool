package uk.beertool.web

import io.ktor.http.encodeURLQueryComponent
import kotlinx.html.*
import uk.beertool.batch.Batch
import uk.beertool.batch.BatchSummary
import uk.beertool.batch.BrewInfo
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeStats
import uk.beertool.recipe.RecipeSummary
import uk.beertool.recipe.unmaltedFraction
import uk.beertool.recipe.inBrewOrder
import uk.beertool.user.User

fun HTML.recipeListPage(user: User, recipes: Page<RecipeSummary>, brews: Map<Long, BrewInfo>, term: String) {
    page("Recipes", user) {
        if (user.canWrite) pageHead("Recipes") { btnLink("/recipes/new", "New recipe", Icon.PLUS, primary = true) }
        else h1 { +"Recipes" }

        form(action = "/recipes", method = FormMethod.get, classes = "search") {
            searchInput(name = "q") {
                value = term
                placeholder = "Search recipes, malts, hops, yeast…"
                attributes["aria-label"] = "Search recipes"
            }
            submitInput(classes = "btn") { value = "Search" }
            if (term.isNotBlank()) btnLink("/recipes", "Clear", Icon.BACK)
        }

        if (recipes.total == 0) {
            div("empty") {
                if (term.isBlank()) +"No recipes yet — write your first one and the numbers will follow."
                else +"Nothing matches “$term”."
            }
            return@page
        }
        scrollTable("fit") {
            thead {
                tr {
                    th(classes = "col-grow") { +"Recipe" }
                    th(classes = "num") { +"ABV" }
                    th(classes = "num") { +"IBU" }
                    th(classes = "num") { +"EBC" }
                    th(classes = "hide-sm") { +"Brewed" }
                }
            }
            tbody {
                recipes.items.forEach { r ->
                    val brewed = brews[r.id]
                    tr {
                        td("col-grow") {
                            a(href = "/recipes/${r.no}", classes = "row-name") { +r.name }
                            span("row-sub") { +(r.style ?: "No style") }
                        }
                        td("num") { +fmt(r.stats.abv, 1) }
                        td("num") { +fmt(r.stats.ibu, 0) }
                        td("num") { +fmt(r.stats.colourEbc, 0) }
                        td("tnum hide-sm") { brewedCell(brewed) }
                    }
                }
            }
        }
        pager(recipes) { p -> recipesHref(term, p) }
    }
}

private fun recipesHref(term: String, page: Int) =
    if (term.isBlank()) "/recipes?page=$page" else "/recipes?q=${term.encodeURLQueryComponent()}&page=$page"

private fun TD.brewedCell(brewed: BrewInfo?) {
    if (brewed == null || brewed.count == 0) {
        span("muted") { +MISSING }
        return
    }
    +"${brewed.count}×"
    brewed.lastBrewed?.let { span("muted") { +" · last $it" } }
}

private fun <T> FlowContent.billTable(
    heading: String,
    rows: List<T>,
    header: TR.() -> Unit,
    tableClass: String = "bill",
    row: TR.(T) -> Unit,
) {
    if (rows.isEmpty()) return
    sectionHead(heading)
    scrollTable(tableClass) {
        thead { tr { header() } }
        tbody { rows.forEach { item -> tr { row(item) } } }
    }
}

fun HTML.recipeViewPage(user: User, recipe: Recipe, stats: RecipeStats, brews: List<BatchSummary>, activeBrewDay: Batch?) {
    page(recipe.name, user) {
        h1 { +recipe.name }

        p("lede") {
            +(recipe.style ?: "No style")
            BeerStyles.urlFor(recipe.style)?.let { url ->
                +" "
                a(href = url, classes = "bjcp-link") { target = "_blank"; attributes["rel"] = "noopener"; +"↗ BJCP" }
            }
        }

        if (user.canWrite) actionBar {
            if (activeBrewDay != null) btnLink("/recipes/${recipe.no}/batches/${activeBrewDay.no}/brew", "Resume brew day", Icon.BREW, primary = true)
            else btnPost("/recipes/${recipe.no}/brew-day", "Start brew day", Icon.BREW, primary = true)
            btnLink("/recipes/${recipe.no}/edit", "Edit recipe", Icon.EDIT)
            deleteButton("/recipes/${recipe.no}/delete", "Delete “${recipe.name}” and all its brews?")
        }
        recipe.description?.let { p { +it } }

        sectionHead("Brew day")
        div("stats") { brewDayTiles(recipe) }

        sectionHead("Estimates")
        div("stats") { statsTiles(stats) }

        billTable("Mash", recipe.mashSteps, TR::mashHead, "bill mash") { m ->
            stepNumberCell()
            numCell(m.tempC, decimals = 1)
            emptyCell()
            emptyCell()
            numCell(m.timeMin)
            emptyCell()
        }

        billTable("Fermentables", recipe.fermentables.inBrewOrder(), TR::fermentableHead) { f ->
            td { +f.name }
            numCell(f.amountKg, decimals = 3)
            td { +prettify(f.type.name) }
            td { +prettify(f.usage.name) }
            numCell(f.boilTimeMin)
            numCell(f.colourEbc, decimals = 0)
            numCell(f.extractPercent, decimals = 1)
        }
        unmaltedWarning(recipe.unmaltedFraction())

        billTable("Hops", recipe.hops.inBrewOrder(), TR::hopHead) { h ->
            td { +h.name }
            numCell(h.amountG, decimals = 0)
            emptyCell()
            td { +prettify(h.usage.name) }
            numCell(h.boilTimeMin)
            numCell(h.alphaAcid, decimals = 1)
        }

        billTable("Yeasts", recipe.yeasts.inBrewOrder(), TR::yeastHead) { y ->
            td { +y.name }
            emptyCell()
            emptyCell()
            td { +prettify(y.usage.name) }
            emptyCell()
            numCell(y.attenuation.asPercent(), decimals = 0)
        }

        billTable("Extras", recipe.extras.inBrewOrder(), TR::extraHead) { e ->
            td { +e.name }
            numCell(e.amount, decimals = 1)
            td { +e.unit.code }
            td { +prettify(e.usage.name) }
            numCell(e.boilTimeMin)
            emptyCell()
        }

        sectionHead("Brews") {
            if (user.canWrite) btnLink("/recipes/${recipe.no}/batches/new", "Log a brew", Icon.BREW)
        }
        if (brews.isEmpty()) div("empty") { +"Not brewed yet." }
        else batchTable(brews, showRecipe = false)
    }
}
