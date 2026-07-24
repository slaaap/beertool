package uk.beertool.web

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.html.dom.createHTMLDocument
import kotlinx.html.dom.serialize
import kotlinx.html.html
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import uk.beertool.recipe.NewRecipe
import uk.beertool.recipe.estimatedStats
import uk.beertool.user.User
import java.time.Instant

@OptIn(ExperimentalSerializationApi::class)
class RecipeFormNamesTest {

    @Test
    fun `should post every ingredient field under the name the DTO decodes it from`() {
        val posted = renderedFormNames()

        rowNames("mashSteps", serializer<MashStepRow>().descriptor).forEach { posted shouldContain it }
        rowNames("fermentables", serializer<FermentableRow>().descriptor).forEach { posted shouldContain it }
        rowNames("hops", serializer<HopRow>().descriptor).forEach { posted shouldContain it }
        rowNames("yeasts", serializer<YeastRow>().descriptor).forEach { posted shouldContain it }
        rowNames("extras", serializer<ExtraRow>().descriptor).forEach { posted shouldContain it }
    }

    @Test
    fun `should post every top-level field under the name the DTO decodes it from`() {
        val posted = renderedFormNames()

        val scalars = serializer<RecipeForm>().descriptor.elementNames.filter { it !in LISTS }

        posted shouldContainAll scalars
    }

    @Test
    fun `should name nothing the DTO would not recognise`() {
        val known = serializer<RecipeForm>().descriptor.elementNames.toSet()

        val unknown = renderedFormNames().filterNot { name ->
            val list = name.substringBefore('.')
            list in known
        }

        unknown shouldBe emptyList()
    }

    private fun renderedFormNames(): List<String> {
        val user = User(id = 1, email = "b@t.uk", displayName = "Brewer", createdAt = Instant.EPOCH)
        val html = createHTMLDocument()
            .html { recipeFormPage(user, null, NewRecipe(name = "").estimatedStats()) }
            .serialize()

        return NAME_ATTR.findAll(html).map { it.groupValues[1] }.toList()
    }

    private fun rowNames(list: String, descriptor: SerialDescriptor) =
        descriptor.elementNames.map { "$list.0.$it" }

    private infix fun List<String>.shouldContain(name: String) = contains(name) shouldBe true

    private companion object {
        val LISTS = setOf("mashSteps", "fermentables", "hops", "yeasts", "extras")

        val NAME_ATTR = Regex("""<(?:input|select|textarea)[^>]*\bname="([^"]+)"""")
    }
}
