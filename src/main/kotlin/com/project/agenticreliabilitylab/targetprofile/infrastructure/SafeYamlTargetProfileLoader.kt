package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDocumentException
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/** Bounded YAML loader that refuses dynamic YAML features before structural mapping begins. */
@Component
class SafeYamlTargetProfileLoader {
    fun load(document: String): Map<String, Any?> {
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Target Profile document exceeds $MAX_DOCUMENT_BYTES bytes"
        }
        require(!document.contains("\${")) { "Target Profile document must not contain environment placeholders" }
        require(!TAG_OR_ALIAS_MARKER.containsMatchIn(document)) {
            "Target Profile document must not contain YAML tags, anchors, or aliases"
        }
        return try {
            Yaml(SafeConstructor(loaderOptions())).load<Any?>(document)
                ?.yamlMap("root", setOf("arl"))
                ?: throw TargetProfileDocumentException("Target Profile document must not be empty")
        } catch (exception: YAMLException) {
            throw TargetProfileDocumentException("Target Profile YAML is invalid", exception)
        }
    }

    private fun loaderOptions(): LoaderOptions = LoaderOptions().apply {
        maxAliasesForCollections = 0
        nestingDepthLimit = MAX_NESTING_DEPTH
        codePointLimit = MAX_DOCUMENT_BYTES
        isAllowDuplicateKeys = false
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 65_536
        const val MAX_NESTING_DEPTH = 20
        val TAG_OR_ALIAS_MARKER = Regex("(?m)(^|\\s)[!&*][A-Za-z_]")
    }
}
