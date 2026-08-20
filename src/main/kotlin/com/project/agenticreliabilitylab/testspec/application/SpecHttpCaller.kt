package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthProvider
import com.project.agenticreliabilitylab.testspec.application.port.SpecExecutionSettings
import com.project.agenticreliabilitylab.testspec.domain.RecordedResponse
import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Sends one call a specification declares.
 *
 * Two kinds of failure are kept apart here. A request that was rejected before anything left ARL - an unresolved
 * reference, a path outside the registered origin, a credential the Runner does not hold - raises, because
 * nothing happened and nothing should be judged. A request that did leave and then failed becomes a recorded
 * response with a failure note, because the Target may already have changed and the run has to account for it.
 */
@Component
class SpecHttpCaller(
    private val transport: TargetReadTransport,
    private val references: SpecReferenceResolver,
    private val authProvider: SpecAuthProvider,
    private val settings: SpecExecutionSettings,
) {
    fun send(
        target: RegisteredTarget,
        call: SpecHttpCall,
        bindings: Map<String, String>,
        requestNumber: Int,
        runId: String,
    ): RecordedResponse {
        val uri = resolveUri(target, call, bindings)
        val headers = buildHeaders(target, call, bindings, runId)
        val body = call.bodyJson?.let { references.resolve(it, bindings) }
            ?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)

        val startedAt = System.nanoTime()
        return try {
            val response = transport.send(target, uri, call.method, headers, body, settings.requestTimeout)
            RecordedResponse(
                requestNumber = requestNumber,
                statusCode = response.statusCode,
                durationMs = startedAt.elapsedMs(),
                body = String(response.body, StandardCharsets.UTF_8),
            )
        } catch (exception: TargetReadTransportException) {
            RecordedResponse(
                requestNumber = requestNumber,
                statusCode = 0,
                durationMs = startedAt.elapsedMs(),
                body = "",
                failure = exception.message ?: "The request did not complete",
            )
        }
    }

    /**
     * Builds the request URI and re-checks the origin.
     *
     * The path went through reference substitution, so it is partly run-time data. Checking the origin after
     * substitution is the point: a captured value that contains a path escape must not be able to send an ARL
     * request somewhere the operator never registered.
     */
    private fun resolveUri(target: RegisteredTarget, call: SpecHttpCall, bindings: Map<String, String>): URI {
        val path = references.resolve(call.path, bindings)
        val relativeUri = SpecRequestPolicy.requireSafeResolvedPath(call.path, path)
        val uri = target.baseUri.resolve(relativeUri)
        if (uri.normalisedOrigin() != target.allowedOrigin.normalisedOrigin()) {
            throw SpecExecutionException("Call path '$path' resolves outside the registered target origin")
        }
        return uri
    }

    private fun buildHeaders(
        target: RegisteredTarget,
        call: SpecHttpCall,
        bindings: Map<String, String>,
        runId: String,
    ): Map<String, String> = buildMap {
        put("Accept", "application/json")
        if (call.bodyJson != null) put("Content-Type", "application/json")
        put(RUN_HEADER, runId)
        val specificationHeaders = references.resolveAll(call.headers, bindings)
        specificationHeaders.keys.forEach { name ->
            SpecRequestPolicy.specificationHeaderViolation(name)?.let { violation ->
                throw SpecExecutionException(violation)
            }
        }
        putAll(specificationHeaders)
        call.authProfile?.let { profile ->
            val authHeaders = authProvider.headersFor(target.id, profile)
            authHeaders.keys.forEach { name ->
                SpecRequestPolicy.authHeaderViolation(name)?.let { violation ->
                    throw SpecExecutionException(violation)
                }
            }
            putAll(authHeaders)
        }
    }

    private fun Long.elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this)

    private fun URI.normalisedOrigin(): String {
        val effectivePort = when {
            port >= 0 -> port
            scheme.equals("https", ignoreCase = true) -> HTTPS_PORT
            else -> HTTP_PORT
        }
        return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort"
    }

    private companion object {
        /** Lets an operator find every request a run made in the Target's own logs. */
        const val RUN_HEADER = "X-ARL-Run-Id"
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
    }
}
