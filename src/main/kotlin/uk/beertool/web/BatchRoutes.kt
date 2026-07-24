package uk.beertool.web

import io.ktor.resources.Resource
import io.ktor.server.html.respondHtml
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import uk.beertool.batch.BatchRepository
import uk.beertool.batch.NewBatch
import uk.beertool.recipe.stats
import java.time.LocalDate

@Resource("/batches")
class BatchesRes(val page: Int = 1)

@Resource("/recipes/{recipeNo}/batches")
class RecipeBatchesRes(val recipeNo: Int)

@Resource("/recipes/{recipeNo}/batches/new")
class BatchNewRes(val recipeNo: Int)

@Resource("/recipes/{recipeNo}/batches/{no}")
class BatchRes(val recipeNo: Int, val no: Int)

@Resource("/recipes/{recipeNo}/batches/{no}/edit")
class BatchEditRes(val recipeNo: Int, val no: Int, val packageToday: Boolean = false)

@Resource("/recipes/{recipeNo}/batches/{no}/brew")
class BatchBrewRes(val recipeNo: Int, val no: Int, val saved: Boolean = false)

@Resource("/recipes/{recipeNo}/batches/{no}/packaged")
class BatchPackagedRes(val recipeNo: Int, val no: Int)

@Resource("/recipes/{recipeNo}/batches/{no}/delete")
class BatchDeleteRes(val recipeNo: Int, val no: Int)

@Resource("/recipes/{recipeNo}/brew-day")
class RecipeStartBrewRes(val recipeNo: Int)

fun Route.batchRoutes() {
    get<BatchesRes> { res ->
        withUser { call, user ->
            call.respondHtml { batchLogPage(user, BatchRepository.listByUser(user.id).paginate(res.page)) }
        }
    }

    get<BatchNewRes> { res ->
        withRecipe(res.recipeNo, write = true) { call, user, recipe ->
            call.respondHtml { batchFormPage(user, recipe, null) }
        }
    }

    post<RecipeBatchesRes> { res ->
        withRecipe(res.recipeNo, write = true) { call, user, recipe ->
            val created = BatchRepository.create(user.id, call.receiveForm<BatchForm>().toNewBatch(recipe.id))
            if (created == null) call.respondRedirect("/recipes")
            else call.respondRedirect("/recipes/${recipe.no}/batches/${created.no}")
        }
    }

    get<BatchRes> { res ->
        withBatch(res.recipeNo, res.no) { call, user, recipe, batch ->
            call.respondHtml { batchViewPage(user, batch, recipe, recipe.stats()) }
        }
    }

    get<BatchEditRes> { res ->
        withBatch(res.recipeNo, res.no, write = true) { call, user, recipe, batch ->
            call.respondHtml { batchFormPage(user, recipe, batch, res.packageToday) }
        }
    }

    post<BatchRes> { res ->
        withBatch(res.recipeNo, res.no, write = true) { call, user, recipe, batch ->
            BatchRepository.update(batch.id, user.id, call.receiveForm<BatchForm>().toNewBatch(recipe.id))
            call.respondRedirect("/recipes/${recipe.no}/batches/${batch.no}")
        }
    }

    post<RecipeStartBrewRes> { res ->
        withRecipe(res.recipeNo, write = true) { call, user, recipe ->
            val target = BatchRepository.activeBrewDay(recipe.id, user.id)
                ?: BatchRepository.create(user.id, NewBatch(recipeId = recipe.id, brewDate = LocalDate.now()))
            if (target == null) call.respondRedirect("/recipes")
            else call.respondRedirect("/recipes/${recipe.no}/batches/${target.no}/brew")
        }
    }

    get<BatchBrewRes> { res ->
        withBatch(res.recipeNo, res.no, write = true) { call, user, recipe, batch ->
            call.respondHtml { brewDayPage(user, batch, recipe, recipe.stats(), saved = res.saved) }
        }
    }

    post<BatchBrewRes> { res ->
        withBatch(res.recipeNo, res.no, write = true) { call, user, recipe, batch ->
            BatchRepository.update(batch.id, user.id, call.receiveForm<BatchForm>().toNewBatch(recipe.id))
            call.respondRedirect("/recipes/${recipe.no}/batches/${batch.no}/brew?saved=true")
        }
    }

    post<BatchPackagedRes> { res ->
        withBatch(res.recipeNo, res.no, write = true) { call, user, recipe, batch ->
            if (batch.measuredFg == null) {
                call.respondRedirect("/recipes/${recipe.no}/batches/${batch.no}/edit?packageToday=true")
            } else {
                BatchRepository.markPackaged(batch.id, user.id, LocalDate.now())
                call.respondRedirect("/recipes/${recipe.no}/batches/${batch.no}")
            }
        }
    }

    post<BatchDeleteRes> { res ->
        withBatch(res.recipeNo, res.no, write = true) { call, user, recipe, batch ->
            BatchRepository.delete(batch.id, user.id)
            call.respondRedirect("/batches")
        }
    }
}
