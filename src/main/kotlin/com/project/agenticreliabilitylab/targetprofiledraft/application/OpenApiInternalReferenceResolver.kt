package com.project.agenticreliabilitylab.targetprofiledraft.application

import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraftException
import org.springframework.stereotype.Component

/** Resolves bounded, document-local OpenAPI Path Item references without fetching external content. */
@Component
class OpenApiInternalReferenceResolver {
    fun resolvePathItem(root: Map<String, Any?>, candidate: Any?): Map<*, *>? {
        var current = candidate as? Map<*, *> ?: return null
        val visitedReferences = mutableSetOf<String>()
        while (true) {
            val reference = current.stringValue(REFERENCE) ?: return current
            require(reference.startsWith(LOCAL_POINTER_PREFIX)) { "OpenAPI external references are not supported" }
            require(visitedReferences.add(reference)) { "OpenAPI internal reference cycle is not supported" }
            current = resolvePointer(root, reference) as? Map<*, *>
                ?: throw TargetProfileDraftException("OpenAPI internal reference must resolve to an object")
        }
    }

    private fun resolvePointer(root: Map<String, Any?>, reference: String): Any? =
        reference.removePrefix(LOCAL_POINTER_PREFIX)
            .split('/')
            .fold(root as Any?) { current, segment ->
                when (current) {
                    is Map<*, *> -> current[segment.unescapePointerSegment()]
                    is List<*> -> current.getOrNull(segment.toIntOrNull() ?: return null)
                    else -> null
                }
            }

    private fun String.unescapePointerSegment(): String = replace("~1", "/").replace("~0", "~")

    private companion object {
        const val REFERENCE = "\$ref"
        const val LOCAL_POINTER_PREFIX = "#/"
    }
}
