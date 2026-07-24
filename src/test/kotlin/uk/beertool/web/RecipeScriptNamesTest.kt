package uk.beertool.web

import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class RecipeScriptNamesTest {

    @Test
    fun `should only look for form fields that the form actually posts`() {
        val known = buildSet {
            addAll(serializer<RecipeForm>().descriptor.elementNames)
            addAll(serializer<FermentableRow>().descriptor.elementNames)
            addAll(serializer<HopRow>().descriptor.elementNames)
            addAll(serializer<YeastRow>().descriptor.elementNames)
            addAll(serializer<ExtraRow>().descriptor.elementNames)
            addAll(serializer<MashStepRow>().descriptor.elementNames)
        }

        val looksFor = SELECTOR.findAll(script()).map { it.groupValues[1].removePrefix(".") }.toSet()

        looksFor.filterNot { it in known } shouldBe emptyList()
    }

    @Test
    fun `should still recognise an ingredient row's indexed name when renumbering it`() {

        val pattern = Regex("""\^\(\[A-Za-z]\+\)\\\.\\d\+\\\.""")

        pattern.containsMatchIn(script()) shouldBe true
        Regex("^([A-Za-z]+)\\.\\d+\\.").containsMatchIn("fermentables.0.amountKg") shouldBe true
    }

    private fun script() = File("src/main/resources/static/js/recipe-form.js").readText()

    private companion object {

        val SELECTOR = Regex("""name\$?="([^"]+)"""")
    }
}
