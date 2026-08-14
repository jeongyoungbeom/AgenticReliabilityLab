package com.project.agenticreliabilitylab.targetprofiledraft.application

import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraftException
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/** Parses an offline OpenAPI document and rejects references or asynchronous operations outside the supplied text. */
@Component
class BoundedOpenApiDocumentParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(document: String): Map<String, Any?> {
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "OpenAPI document exceeds $MAX_DOCUMENT_BYTES bytes"
        }
        require(!document.contains("\${")) { "OpenAPI document must not contain environment placeholders" }
        require(!YAML_DYNAMIC_MARKER.containsMatchIn(document)) {
            "OpenAPI document must not contain YAML tags, anchors, or aliases"
        }
        val parsed = if (document.trimStart().startsWith('{')) parseJson(document) else parseYaml(document)
        inspectSafety(parsed)
        return parsed
    }

    private fun parseJson(document: String): Map<String, Any?> = try {
        objectMapper.readValue(document, MAP_TYPE)
    } catch (exception: JacksonException) {
        throw TargetProfileDraftException("OpenAPI JSON is invalid", exception)
    }

    private fun parseYaml(document: String): Map<String, Any?> = try {
        @Suppress("UNCHECKED_CAST")
        (Yaml(SafeConstructor(loaderOptions())).load<Any?>(document) as? Map<String, Any?>)
            ?: throw TargetProfileDraftException("OpenAPI document root must be an object")
    } catch (exception: YAMLException) {
        throw TargetProfileDraftException("OpenAPI YAML is invalid", exception)
    }

    private fun inspectSafety(value: Any?) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                when (key) {
                    "callbacks", "webhooks" -> {
                        throw TargetProfileDraftException("OpenAPI callbacks and webhooks are not supported")
                    }
                    "\$ref" -> require(child is String && child.startsWith("#/")) {
                        "OpenAPI external references are not supported"
                    }
                }
                inspectSafety(child)
            }
            is List<*> -> value.forEach(::inspectSafety)
        }
    }

    private fun loaderOptions(): LoaderOptions = LoaderOptions().apply {
        maxAliasesForCollections = 0
        nestingDepthLimit = MAX_NESTING_DEPTH
        codePointLimit = MAX_DOCUMENT_BYTES
        isAllowDuplicateKeys = false
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 1_048_576
        const val MAX_NESTING_DEPTH = 40
        val YAML_DYNAMIC_MARKER = Regex("(?m)(^|\\s)[!&*][A-Za-z_]")
        val MAP_TYPE = object : tools.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}
