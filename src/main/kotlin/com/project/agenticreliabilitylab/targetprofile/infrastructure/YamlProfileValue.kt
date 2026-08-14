package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDocumentException

internal fun Any?.yamlMap(label: String, allowedFields: Set<String>): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { (key, value) ->
        val field = key as? String
            ?: throw TargetProfileDocumentException("$label contains a non-string field name")
        field to value
    }?.also { it.ensureOnly(label, allowedFields) }
        ?: throw TargetProfileDocumentException("$label must be an object")

internal fun Map<String, Any?>.requiredMap(key: String, allowedFields: Set<String>): Map<String, Any?> =
    get(key).yamlMap("'$key'", allowedFields)

internal fun Map<String, Any?>.optionalMap(key: String, allowedFields: Set<String>): Map<String, Any?>? =
    get(key)?.yamlMap("'$key'", allowedFields)

internal fun Map<String, Any?>.requiredList(key: String): List<Any?> =
    get(key) as? List<*> ?: throw TargetProfileDocumentException("'$key' must be an array")

internal fun Map<String, Any?>.optionalList(key: String): List<Any?>? =
    get(key)?.let { value -> value as? List<*> ?: throw TargetProfileDocumentException("'$key' must be an array") }

internal fun List<Any?>.singleMap(label: String, allowedFields: Set<String>): Map<String, Any?> {
    require(size == 1) { "$label must contain exactly one registration" }
    return single().yamlMap(label, allowedFields)
}

internal fun List<Any?>.singleMatchingMap(
    label: String,
    targetSystemId: String,
    allowedFields: Set<String>,
): Map<String, Any?>? {
    if (isEmpty()) return null
    require(size == 1) { "$label must contain exactly one registration when it is provided" }
    val registration = single().yamlMap(label, allowedFields)
    require(registration.optionalString("target-system-id") == targetSystemId) {
        "$label registration must match target '$targetSystemId'"
    }
    return registration
}

internal fun Map<String, Any?>.ensureOnly(label: String, allowedFields: Set<String>) {
    val unknown = keys - allowedFields
    require(unknown.isEmpty()) { "$label contains unsupported fields: ${unknown.sorted().joinToString()}" }
}

internal fun Map<String, Any?>.requiredString(key: String): String =
    optionalString(key) ?: throw TargetProfileDocumentException("'$key' is required")

internal fun Map<String, Any?>.optionalString(key: String): String? = when (val value = get(key)) {
    null -> null
    is String -> value
    else -> throw TargetProfileDocumentException("'$key' must be a string")
}
