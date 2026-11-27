package uk.beertool.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
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
import uk.beertool.batch.NewBatch
import uk.beertool.db.TestDatabase
import uk.beertool.recipe.FermentableType
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.RecipeFermentable
import uk.beertool.recipe.RecipeRepository
import uk.beertool.user.SingleUser
import uk.beertool.user.UserRepository
import java.util.UUID

class BrewDayReadingsTest {

    @BeforeEach
    fun setUp() {
        TestDatabase.ensureReady()
        SingleUser.email = null
    }

    @Test
    fun `should take a small pot's volume to a tenth of a litre and keep it`() = brewDayApp { brewer ->
        val client = signedIn(brewer)
        val page = client.get(brewer.url).bodyAsText()

        val saved = client.post(brewer.url) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("brewDate=2026-11-27&measuredPostBoilVolumeL=1.8")
        }
        val after = client.get(brewer.url).bodyAsText()

        attribute(page, "measuredPostBoilVolumeL", "step") shouldBe "0.1"
        saved.status shouldBe HttpStatusCode.Found
        after shouldContain Regex("""name="measuredPostBoilVolumeL"[^>]*value="1.8"""")
    }

    @Test
    fun `should mark the planned figures as the plan, so an empty field is not taken for a saved one`() = brewDayApp { brewer ->
        val client = signedIn(brewer)

        val page = client.get(brewer.url).bodyAsText()

        attribute(page, "measuredFermenterVolumeL", "placeholder") shouldStartWith "plan "
        attribute(page, "measuredOg", "placeholder") shouldStartWith "plan "
    }

    private data class Brewer(val email: String, val url: String)

    private fun attribute(page: String, field: String, attribute: String) =
        Regex("""name="$field"[^>]*$attribute="([^"]*)"""").find(page)!!.groupValues[1]

    private suspend fun ApplicationTestBuilder.signedIn(brewer: Brewer): HttpClient {
        val client = createClient { install(HttpCookies); followRedirects = false }
        client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=${brewer.email}&password=secret123")
        }
        return client
    }

    private fun newBrewer(): Brewer {
        val email = "brew-day-${UUID.randomUUID()}@beertool.test"
        val user = UserRepository.create(email, PasswordHasher.hash("secret123"), "Brewer")
        val recipe = RecipeRepository.create(user.id, NewRecipe(name = "Pale", fermentables = listOf(RecipeFermentable("Maris Otter", 4.5, FermentableType.MALT))))
        val batch = BatchRepository.create(user.id, NewBatch(recipeId = recipe.id))!!
        return Brewer(email, "/recipes/${recipe.no}/batches/${batch.no}/brew")
    }

    private fun brewDayApp(block: suspend ApplicationTestBuilder.(Brewer) -> Unit) = testApplication {
        application {
            install(Resources)
            configureSecurity()
            configureRouting()
        }
        block(newBrewer())
    }
}
