package com.project.agenticreliabilitylab.testspec.application

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Applies the structural subset of JSON Schema used by the test-spec contract.
 *
 * The schema is the single list of allowed fields and primitive types. Keeping that list out of the parser means
 * an unknown model-produced field cannot be silently ignored by one mapper while another mapper knows about it.
 */
@Component
class TestSpecSchemaValidator(
    private val objectMapper: ObjectMapper,
) {
    private val rootSchema: JsonNode = loadSchema()

    fun validate(document: JsonNode) {
        val violations = mutableListOf<String>()
        validateNode(document, rootSchema, ROOT_PATH, violations)
        if (violations.isNotEmpty()) {
            throw SpecParseException(
                "Specification does not match JSON Schema: ${violations.joinToString("; ")}",
            )
        }
    }

    private fun validateNode(
        node: JsonNode,
        unresolvedSchema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val schema = resolve(unresolvedSchema)
        val type = schema.path("type").asString()
        if (type.isNotBlank() && !matchesType(node, type)) {
            violations.add("$path must be $type")
            return
        }

        validateEnum(node, schema, path, violations)
        when (type) {
            "object" -> validateObject(node, schema, path, violations)
            "array" -> validateArray(node, schema, path, violations)
            "integer", "number" -> validateNumberBounds(node, schema, path, violations)
            "string" -> validateMinLength(node, schema, path, violations)
        }
    }

    private fun validateObject(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        schema.path("required").values().forEach { required ->
            val name = required.asString()
            if (node.path(name).isMissingNode) violations.add("${childPath(path, name)} is required")
        }

        val properties = schema.path("properties")
        val additional = schema.path("additionalProperties")
        node.propertyNames().forEach { name ->
            val propertySchema = properties.path(name)
            when {
                !propertySchema.isMissingNode ->
                    validateNode(node.path(name), propertySchema, childPath(path, name), violations)
                additional.isObject ->
                    validateNode(node.path(name), additional, childPath(path, name), violations)
                additional.asString() == "false" ->
                    violations.add("${childPath(path, name)} is not allowed by JSON Schema")
            }
        }
    }

    private fun validateArray(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val minimum = schema.path("minItems").asString().toIntOrNull()
        if (minimum != null && node.size() < minimum) violations.add("$path must contain at least $minimum item(s)")

        val itemSchema = schema.path("items")
        if (itemSchema.isMissingNode) return
        node.values().forEachIndexed { index, item ->
            validateNode(item, itemSchema, "$path[$index]", violations)
        }
    }

    private fun validateEnum(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val allowed = schema.path("enum")
        if (allowed.isArray && allowed.values().none { candidate -> candidate == node }) {
            violations.add(
                "$path must be one of ${allowed.values().joinToString { it.asString() }} " +
                    "but was '${node.asString()}'",
            )
        }
    }

    private fun validateNumberBounds(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val value = node.asString().toDouble()
        schema.path("minimum").asString().toDoubleOrNull()?.let { minimum ->
            if (value < minimum) violations.add("$path must be at least ${minimum.toLong()}")
        }
        schema.path("maximum").asString().toDoubleOrNull()?.let { maximum ->
            if (value > maximum) violations.add("$path must be at most ${maximum.toLong()}")
        }
    }

    private fun validateMinLength(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val minimum = schema.path("minLength").asString().toIntOrNull() ?: return
        if (node.asString().length < minimum) violations.add("$path must contain at least $minimum character(s)")
    }

    private fun resolve(schema: JsonNode): JsonNode {
        val reference = schema.path("\$ref").asString()
        if (reference.isBlank()) return schema
        if (!reference.startsWith(LOCAL_REFERENCE_PREFIX)) {
            error("Only local JSON Schema references are supported: $reference")
        }
        return reference.removePrefix(LOCAL_REFERENCE_PREFIX)
            .split('/')
            .fold(rootSchema) { current, segment -> current.path(segment) }
    }

    private fun matchesType(node: JsonNode, type: String): Boolean = when (type) {
        "object" -> node.isObject
        "array" -> node.isArray
        "string" -> node.isString
        "integer" -> node.isIntegralNumber
        "number" -> node.isNumber
        "boolean" -> node.isBoolean
        else -> error("Unsupported JSON Schema type '$type'")
    }

    private fun childPath(parent: String, child: String): String = "$parent.$child"

    private fun loadSchema(): JsonNode {
        val stream = requireNotNull(javaClass.getResourceAsStream(SCHEMA_RESOURCE)) {
            "Test specification JSON Schema is missing at $SCHEMA_RESOURCE"
        }
        return stream.use { input -> objectMapper.readTree(input) }
    }

    private companion object {
        const val LOCAL_REFERENCE_PREFIX = "#/"
        const val ROOT_PATH = "$"
        const val SCHEMA_RESOURCE = "/schema/test-spec.schema.json"
    }
}
