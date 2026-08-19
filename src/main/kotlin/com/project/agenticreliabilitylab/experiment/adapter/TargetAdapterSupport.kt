package com.project.agenticreliabilitylab.experiment.adapter

import com.project.agenticreliabilitylab.experiment.domain.ExternalOperationOutcomeUnknownException
import com.project.agenticreliabilitylab.experiment.domain.TargetPreflightFailedException
import com.project.agenticreliabilitylab.experiment.domain.TargetResource
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import tools.jackson.databind.JsonNode
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Checks shared by every Target adapter.
 *
 * Origin matching is a security boundary, so it lives in exactly one place rather than being copied per adapter where
 * the copies could drift. It is also a pre-flight check: a mismatch means nothing was sent, which is why it raises
 * [TargetPreflightFailedException].
 */
internal fun requireRegisteredOrigin(uri: URI, target: RegisteredTarget, label: String) {
    if (uri.normalizedOrigin() != target.allowedOrigin.normalizedOrigin()) {
        throw TargetPreflightFailedException("Registered $label is outside the target allowed origin")
    }
}

internal fun URI.normalizedOrigin(): String {
    val effectivePort = when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> HTTPS_PORT
        else -> HTTP_PORT
    }
    return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort"
}

/**
 * Reads a numeric observation the Target reported.
 *
 * A malformed number means the result contract could not be read, and the workload may already have run, so this is an
 * undetermined outcome rather than a validation failure.
 */
internal fun JsonNode.requiredNonNegativeInt(field: String, label: String): Int =
    path(field).asString().toIntOrNull()?.takeIf { value -> value >= 0 }
        ?: throw ExternalOperationOutcomeUnknownException("$label '$field' must be a non-negative integer")

internal fun JsonNode.requiredNonNegativeLong(field: String, label: String): Long =
    path(field).asString().toLongOrNull()?.takeIf { value -> value >= 0 }
        ?: throw ExternalOperationOutcomeUnknownException("$label '$field' must be a non-negative integer")

/** Identifiers the Target reports are untrusted, so they are constrained before they reach Evidence. */
internal fun JsonNode.toResources(defaultNamespace: String): List<TargetResource> {
    if (!isArray) return emptyList()
    return values().map { resource -> resource.toResource(defaultNamespace) }
}

private fun JsonNode.toResource(defaultNamespace: String): TargetResource {
    val type = path("type").asString()
    val id = path("id").asString()
    val namespace = path("namespace").asString().takeIf(String::isNotBlank) ?: defaultNamespace
    val safe = SAFE_VALUE_PATTERN.matches(type) &&
        SAFE_VALUE_PATTERN.matches(id) &&
        SAFE_VALUE_PATTERN.matches(namespace)
    if (!safe) {
        throw ExternalOperationOutcomeUnknownException(
            "Target resource type, id and namespace must be safe identifiers",
        )
    }
    return TargetResource(type = type, id = id, namespace = namespace)
}

internal fun JsonNode.safeIdentifierOrNull(field: String): String? =
    path(field).asString().takeIf(SAFE_VALUE_PATTERN::matches)

internal fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

internal val SAFE_VALUE_PATTERN = Regex("[A-Za-z0-9._:@/-]{1,300}")
private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80
