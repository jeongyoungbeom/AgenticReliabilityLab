package com.project.agenticreliabilitylab.targetprofiledraft.application

import java.net.URI
import java.net.URISyntaxException

internal fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? =
    (get(key) as? Map<*, *>)?.entries?.associate { (name, value) ->
        val property = name as? String ?: return null
        property to value
    }

internal fun Map<String, Any?>.objectListValue(key: String): List<Map<String, Any?>>? =
    (get(key) as? List<*>)?.mapNotNull { entry ->
        (entry as? Map<*, *>)?.entries?.associate { (name, value) ->
            val property = name as? String ?: return@mapNotNull null
            property to value
        }
    }

internal fun Map<*, *>.stringValue(key: String): String? = get(key) as? String

internal fun Map<*, *>.responseStatusCodes(): Set<Int> = (this[RESPONSES] as? Map<*, *>)
    ?.keys
    ?.mapNotNull { (it as? String)?.toIntOrNull() }
    ?.filterTo(linkedSetOf()) { it in SUCCESS_STATUS_CODES }
    .orEmpty()

internal fun Map<*, *>.hasParameters(): Boolean = (this[PARAMETERS] as? List<*>)?.isNotEmpty() == true

internal fun String.isStaticRelativePath(): Boolean {
    if (!startsWith('/') || startsWith("//") || contains('{')) return false
    return try {
        val uri = URI(this)
        uri.query == null && uri.fragment == null
    } catch (_: URISyntaxException) {
        false
    }
}

internal fun String.isSafeServerOrigin(): Boolean = try {
    val uri = URI(this)
    uri.isAbsolute && uri.scheme in SAFE_SCHEMES && uri.host != null && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && uri.rawPath.isNullOrEmptyOrRoot() && !contains('{')
} catch (_: URISyntaxException) {
    false
}

private fun String?.isNullOrEmptyOrRoot(): Boolean = isNullOrEmpty() || this == "/"

internal fun String.toDraftIdentifier(defaultValue: String): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .take(MAX_DRAFT_ID_LENGTH)
    .ifBlank { defaultValue }

internal fun String.toBoundedTitle(defaultValue: String): String = trim()
    .take(MAX_DRAFT_TITLE_LENGTH)
    .ifBlank { defaultValue }

internal const val GET = "get"
internal const val PARAMETERS = "parameters"
internal const val REQUEST_BODY = "requestBody"
internal const val RESPONSES = "responses"
internal const val SUMMARY = "summary"
internal const val DESCRIPTION = "description"
internal const val OPERATION_ID = "operationId"
internal const val MAX_DRAFT_ID_LENGTH = 100
internal const val MAX_DRAFT_TITLE_LENGTH = 200
internal const val MAX_DRAFT_DESCRIPTION_LENGTH = 1_000
internal val SUCCESS_STATUS_CODES = 200..299
internal val SAFE_SCHEMES = setOf("http", "https")
