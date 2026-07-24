package uk.beertool.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.RoutingContext
import uk.beertool.batch.Batch
import uk.beertool.batch.BatchRepository
import uk.beertool.recipe.Recipe
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.User

suspend fun RoutingContext.withUser(
    write: Boolean = false,
    block: suspend (ApplicationCall, User) -> Unit,
) {
    val user = call.currentUser()
    when {
        user == null -> call.respondRedirect("/login")
        write && !user.canWrite -> call.respondRedirect("/login")
        else -> block(call, user)
    }
}

suspend fun RoutingContext.withRecipe(
    no: Int,
    write: Boolean = false,
    onMissing: String = "/recipes",
    block: suspend (ApplicationCall, User, Recipe) -> Unit,
) {
    withUser(write) { call, user ->
        val recipe = RecipeRepository.findByNo(no, user.id)
        if (recipe == null) call.respondRedirect(onMissing) else block(call, user, recipe)
    }
}

suspend fun RoutingContext.withBatch(
    recipeNo: Int,
    no: Int,
    write: Boolean = false,
    block: suspend (ApplicationCall, User, Recipe, Batch) -> Unit,
) {
    withUser(write) { call, user ->
        val recipe = RecipeRepository.findByNo(recipeNo, user.id)
        val batch = recipe?.let { BatchRepository.findByRecipeAndNo(it.id, no, user.id) }
        if (recipe == null || batch == null) call.respondRedirect("/batches") else block(call, user, recipe, batch)
    }
}
