package uk.beertool.web

import io.ktor.http.ContentType
import io.ktor.resources.Resource
import io.ktor.server.html.respondHtml
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.BatchSummary
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.recipe.estimatedStats
import uk.beertool.recipe.stats

@Resource("/recipes")
class RecipesRes(val q: String? = null, val page: Int = 1)

@Resource("/recipes/new")
class RecipeNewRes

@Resource("/recipes/calculate")
class RecipeCalcRes

@Resource("/recipes/{no}")
class RecipeRes(val no: Int)

@Resource("/recipes/{no}/edit")
class RecipeEditRes(val no: Int)

@Resource("/recipes/{no}/delete")
class RecipeDeleteRes(val no: Int)

fun Route.recipeRoutes() {
    get<RecipesRes> { res ->
        withUser { call, user ->
            val term = res.q.orEmpty()
            call.respondHtml {
                recipeListPage(
                    user = user,
                    recipes = RecipeRepository.search(user.id, term).paginate(res.page),
                    brews = BatchRepository.brewInfoByRecipe(user.id),
                    term = term,
                )
            }
        }
    }

    get<RecipeNewRes> {
        withUser(write = true) { call, user ->
            call.respondHtml { recipeFormPage(user, null, NewRecipe(name = "").estimatedStats()) }
        }
    }

    post<RecipesRes> {
        withUser(write = true) { call, user ->
            val created = RecipeRepository.create(user.id, call.receiveForm<RecipeForm>().toNewRecipe(user.preferences))
            call.respondRedirect("/recipes/${created.no}")
        }
    }

    post<RecipeCalcRes> {
        withUser(write = true) { call, user ->
            val stats = call.receiveForm<RecipeForm>().toNewRecipe(user.preferences).estimatedStats()
            call.respondText(statsFragmentHtml(stats), ContentType.Text.Html)
        }
    }

    get<RecipeRes> { res ->
        withRecipe(res.no) { call, user, recipe ->
            call.respondHtml {
                val brews = BatchRepository.listByRecipe(recipe.id, user.id).map { BatchSummary(it, recipe.name, recipe.no) }
                val active = BatchRepository.activeBrewDay(recipe.id, user.id)
                recipeViewPage(user, recipe, recipe.stats(), brews, active)
            }
        }
    }

    get<RecipeEditRes> { res ->
        withRecipe(res.no, write = true) { call, user, recipe ->
            call.respondHtml { recipeFormPage(user, recipe, recipe.stats()) }
        }
    }

    post<RecipeRes> { res ->
        withRecipe(res.no, write = true) { call, user, recipe ->
            RecipeRepository.update(recipe.id, user.id, call.receiveForm<RecipeForm>().toNewRecipe(user.preferences))
            call.respondRedirect("/recipes/${recipe.no}")
        }
    }

    post<RecipeDeleteRes> { res ->
        withRecipe(res.no, write = true) { call, user, recipe ->
            RecipeRepository.delete(recipe.id, user.id)
            call.respondRedirect("/recipes")
        }
    }
}
