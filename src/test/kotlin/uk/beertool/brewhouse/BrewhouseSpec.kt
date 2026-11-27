package uk.beertool.brewhouse

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper

// The published spec as a validator, so a response or a model that drifts from it fails a test.
object BrewhouseSpec {
    private val root: JsonNode = YAMLMapper().readTree(Brewhouse::class.java.getResource("/brewhouse/openapi.yaml")!!.readText())
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    fun documents(method: String, path: String, status: Int): Boolean = response(method, path, status) != null

    // Problems with the body, or null when the spec gives this response no JSON body to check.
    fun bodyProblems(method: String, path: String, status: Int, body: String): List<String>? {
        val response = response(method, path, status) ?: return listOf("$method $path does not document $status")
        val schema = response.path("content").path("application/json").get("schema") ?: return null
        return check(schema, body)
    }

    fun componentProblems(name: String, json: String): List<String> = check(root.at("/components/schemas/$name"), json)

    private fun response(method: String, path: String, status: Int): JsonNode? {
        val response = root.path("paths").path(path).path(method.lowercase()).path("responses").get(status.toString()) ?: return null
        return response.get("\$ref")?.let { root.at(it.asString().removePrefix("#")) } ?: response
    }

    private fun check(schema: JsonNode, json: String): List<String> {
        val document = (schema.deepCopy() as ObjectNode).apply { set("components", root.get("components")) }
        return registry.getSchema(document).validate(json, InputFormat.JSON).map { it.toString() }
    }
}
