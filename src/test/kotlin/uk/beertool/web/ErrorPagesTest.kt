package uk.beertool.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.resources.Resources
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.beertool.auth.PasswordHasher
import uk.beertool.auth.configureSecurity
import uk.beertool.batch.BatchRepository
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.SingleUser
import uk.beertool.user.User
import uk.beertool.user.UserRepository
import java.util.UUID

class ErrorPagesTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
    }

    @Test
    fun `should log a brew whose OG was typed as gravity points`() = webApp { client, user ->
        val recipe = newRecipe(user)

        val response = client.postForm("/recipes/${recipe.no}/batches", "measuredOg=1052")

        response.status shouldBe HttpStatusCode.Found
        BatchRepository.listByRecipe(recipe.id, user.id).single().measuredOg shouldBe 1.052
    }

    @Test
    fun `should explain an impossible gravity on a page instead of failing the save`() = webApp { client, user ->
        val recipe = newRecipe(user)

        val response = client.postForm("/recipes/${recipe.no}/batches", "measuredOg=52")

        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText() shouldContain "doesn't look like a gravity"
        BatchRepository.listByRecipe(recipe.id, user.id).size shouldBe 0
    }

    @Test
    fun `should explain a settings form whose losses leave nothing for the fermenter`() = webApp { client, _ ->
        val response = client.postForm("/settings", "preBoilVolumeL=5&boilOffL=6")

        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText() shouldContain "leave nothing for the fermenter"
    }

    @Test
    fun `should answer a malformed address with a friendly page`() = webApp { client, _ ->
        val response = client.get("/recipes/not-a-number")

        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText() shouldContain "Go back"
    }

    private suspend fun HttpClient.postForm(path: String, body: String) = post(path) {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(body)
    }

    private fun newRecipe(user: User) = RecipeRepository.create(
        user.id,
        NewRecipe(name = "Pale Ale", fermentables = listOf(RecipeFermentable(name = "Pale Malt", amountKg = 4.5))),
    )

    private fun webApp(block: suspend ApplicationTestBuilder.(HttpClient, User) -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureErrorPages()
            configureRouting()
        }
        val email = "err-${UUID.randomUUID()}@beertool.test"
        val user = UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        val client = createClient { install(HttpCookies); followRedirects = false }
        client.postForm("/login", "email=$email&password=secret123")
        block(client, user)
    }
}
