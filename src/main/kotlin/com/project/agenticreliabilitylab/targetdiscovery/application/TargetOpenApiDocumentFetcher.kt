package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import com.project.agenticreliabilitylab.targetprofile.domain.toRegisteredTarget
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** Fetches only Profile-owned OpenAPI paths through the existing CIDR-pinned Target transport. */
@Component
class TargetOpenApiDocumentFetcher(
    private val transport: TargetReadTransport,
) {
    fun fetch(version: TargetProfileVersion): String {
        val path = version.definition.target.declaredOpenApiPaths().singleOrNull() ?: throw IllegalArgumentException(
            "Target '${version.targetSystemId}' does not declare an OpenAPI path"
        )
        return fetch(version, path)
    }

    fun fetch(version: TargetProfileVersion, path: String): String {
        require(path in version.definition.target.declaredOpenApiPaths()) {
            "Target '${version.targetSystemId}' does not declare OpenAPI path '$path'"
        }
        val target = version.definition.target.toRegisteredTarget(Instant.EPOCH, Instant.EPOCH)
        val uri = target.baseUri.resolve(path)
        require(uri.normalizedOrigin() == target.allowedOrigin.normalizedOrigin()) {
            "Target OpenAPI path resolves outside the registered allowed origin"
        }
        val timeout = version.definition.genericHttp?.requestTimeout ?: DEFAULT_TIMEOUT
        val response = transport.send(
            target = target,
            uri = uri,
            method = "GET",
            headers = mapOf("Accept" to OPENAPI_ACCEPT),
            body = ByteArray(0),
            timeout = timeout,
        )
        if (response.statusCode in REDIRECT_STATUS) {
            throw ClientRequestException(
                code = "OPENAPI_REDIRECT_REFUSED",
                message = "Target OpenAPI path returned a redirect; redirects are not followed",
            )
        }
        if (response.statusCode !in SUCCESS_STATUS) {
            throw ClientRequestException(
                code = "OPENAPI_FETCH_FAILED",
                message = "Target OpenAPI path returned HTTP ${response.statusCode}",
            )
        }
        return decodeUtf8(response.body)
    }

    private fun decodeUtf8(body: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
    } catch (exception: CharacterCodingException) {
        throw ClientRequestException(
            code = "OPENAPI_INVALID_ENCODING",
            message = "Target OpenAPI document must be valid UTF-8",
            cause = exception,
        )
    }

    private fun URI.normalizedOrigin(): String {
        val effectivePort = if (port >= 0) {
            port
        } else if (scheme.equals("https", true)) {
            HTTPS_PORT
        } else {
            HTTP_PORT
        }
        return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort"
    }

    private companion object {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        const val OPENAPI_ACCEPT = "application/json, application/yaml, text/yaml"
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val SUCCESS_STATUS = 200..299
        val REDIRECT_STATUS = 300..399
    }
}
