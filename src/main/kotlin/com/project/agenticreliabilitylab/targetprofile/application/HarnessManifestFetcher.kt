package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.toRegisteredTarget
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.CharacterCodingException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Uses the same CIDR-pinned transport as OpenAPI discovery; credentials are never returned or logged. */
@Component
class HarnessManifestFetcher(private val transport: TargetReadTransport) {
    fun fetch(target: TargetRegistrationDefinition, key: String): String =
        get(target, requireNotNull(target.harnessManifestPath), key).second

    fun state(target: TargetRegistrationDefinition, key: String): Pair<String, String> =
        get(target, "/api/harness/state", key)

    @Suppress("ThrowsCount") // Transport, protocol and encoding failures have distinct safe client errors.
    private fun get(target: TargetRegistrationDefinition, path: String, key: String): Pair<String, String> {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH && key.none { it == '\r' || it == '\n' }) {
            "Harness credential is invalid"
        }
        val registered = target.toRegisteredTarget(Instant.EPOCH, Instant.EPOCH)
        val runId = UUID.randomUUID().toString()
        val response = try { transport.send(
            target = registered,
            uri = registered.baseUri.resolve(path),
            method = "GET",
            headers = mapOf(
                "Accept" to "application/json",
                "X-ARL-Harness-Key" to key,
                "X-ARL-Harness-Version" to "1",
                "X-ARL-Run-Id" to runId,
            ),
            body = ByteArray(0),
            timeout = Duration.ofSeconds(5),
        ) } catch (exception: TargetReadTransportException) {
            throw ClientRequestException("HARNESS_UNREACHABLE", "Harness endpoint could not be reached", exception)
        }
        if (response.statusCode != HTTP_OK) {
            throw ClientRequestException(
                "HARNESS_READ_FAILED", "Harness read returned HTTP ${response.statusCode}",
            )
        }
        require(response.headers["x-arl-harness-version"] == "1") {
            "Harness response has an unsupported version header"
        }
        require(response.headers["content-type"]?.substringBefore(';') == "application/json") {
            "Harness response must be JSON"
        }
        require(response.body.size <= MAX_MANIFEST_BYTES) { "Harness manifest is too large" }
        val document = try { StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(response.body)).toString()
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException("Harness response must use UTF-8", exception)
        }
        return runId to document
    }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_KEY_LENGTH = 8_192
        const val MAX_MANIFEST_BYTES = 1_048_576
    }
}
