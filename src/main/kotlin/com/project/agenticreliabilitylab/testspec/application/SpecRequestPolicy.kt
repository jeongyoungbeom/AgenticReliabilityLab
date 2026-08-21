package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import java.net.URI

/** Shared request boundary used both when a specification is approved and immediately before a request is sent. */
internal object SpecRequestPolicy {
    private val placeholder = Regex("\\{\\{[A-Za-z0-9_.-]+}}|\\{[A-Za-z0-9_.-]+}")
    private val profilePlaceholder = Regex("(?<!\\{)\\{[A-Za-z0-9_.-]+}(?!})")
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val runnerManagedHeaders = setOf(
        "accept",
        "accept-encoding",
        "connection",
        "content-length",
        "content-type",
        "host",
        "transfer-encoding",
        "x-arl-run-id",
        // Attribution depends on this header appearing on workload requests and nowhere else. A specification
        // that could set it on a setup call would make fixture creation look like the work being judged.
        "x-arl-trial",
    )
    private val credentialHeaderMarkers = setOf(
        "api-key",
        "apikey",
        "authorization",
        "cookie",
        "password",
        "secret",
        "token",
    )

    fun registeredPathMatches(registeredTemplate: String, callTemplate: String): Boolean =
        templateRegex(registeredTemplate).matches(callTemplate)

    fun specificationPathViolation(path: String): String? = when {
        profilePlaceholder.containsMatchIn(path) ->
            "Call path '$path' must use a {{...}} runtime reference rather than a Profile {name} placeholder"
        else -> null
    }

    fun requireSafeResolvedPath(callTemplate: String, resolved: String): URI {
        val uri = try {
            URI(resolved)
        } catch (exception: IllegalArgumentException) {
            invalidPath("Call path is not a valid target-relative URI", exception)
        }
        if (uri.hasExternalComponents() || uri.hasUnsupportedSuffix() || uri.hasInvalidRawPath()) {
            invalidPath("Call path must be a target-relative path without query or fragment")
        }
        if (uri.path.split('/').any { segment -> segment == ".." }) {
            invalidPath("Call path must not contain path traversal")
        }
        if (!templateRegex(callTemplate).matches(uri.path)) {
            invalidPath("Resolved call path escapes the approved path template")
        }
        return uri
    }

    fun specificationHeaderViolation(name: String): String? = when {
        !headerName.matches(name) -> "Header '$name' is not a valid HTTP header name"
        name.lowercase() in runnerManagedHeaders -> "Header '$name' is managed by the Runner"
        credentialHeaderMarkers.any(name.lowercase()::contains) ->
            "Header '$name' must come from an authProfile rather than the specification document"
        else -> null
    }

    fun authHeaderViolation(name: String): String? = when {
        !headerName.matches(name) -> "Auth header '$name' is not a valid HTTP header name"
        name.lowercase() in runnerManagedHeaders -> "Auth header '$name' is managed by the Runner"
        else -> null
    }

    private fun URI.hasExternalComponents(): Boolean = isAbsolute || host != null || userInfo != null

    private fun URI.hasUnsupportedSuffix(): Boolean = query != null || fragment != null

    private fun URI.hasInvalidRawPath(): Boolean = rawPath?.startsWith('/') != true || rawPath.startsWith("//")

    private fun invalidPath(message: String, cause: Throwable? = null): Nothing =
        throw SpecExecutionException(message, cause)

    private fun templateRegex(template: String): Regex {
        val pattern = buildString {
            append('^')
            var cursor = 0
            placeholder.findAll(template).forEach { match ->
                append(Regex.escape(template.substring(cursor, match.range.first)))
                append("[^/?#]+")
                cursor = match.range.last + 1
            }
            append(Regex.escape(template.substring(cursor)))
            append('$')
        }
        return Regex(pattern)
    }
}
