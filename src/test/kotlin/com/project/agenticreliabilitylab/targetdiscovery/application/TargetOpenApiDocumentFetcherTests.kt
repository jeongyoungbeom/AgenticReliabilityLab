package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.domain.TargetReadResponse
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetOpenApiDocumentFetcherTests {
    @Test
    fun `fetches the relative OpenAPI path through the registered Target transport`() {
        val transport = RecordingTransport(TargetReadResponse(200, "{\"openapi\":\"3.0.1\"}".toByteArray()))
        val document = TargetOpenApiDocumentFetcher(transport).fetch(version())

        assertEquals("{\"openapi\":\"3.0.1\"}", document)
        assertEquals(URI("http://127.0.0.1:18080/api-docs/product"), transport.uri)
        assertEquals("GET", transport.method)
    }

    @Test
    fun `refuses a redirect without following it`() {
        val exception = assertFailsWith<ClientRequestException> {
            TargetOpenApiDocumentFetcher(RecordingTransport(TargetReadResponse(302, ByteArray(0)))).fetch(version())
        }

        assertEquals("OPENAPI_REDIRECT_REFUSED", exception.code)
    }

    private fun version() = TargetProfileVersion(
        id = UUID.randomUUID(),
        targetSystemId = "sideproject-local",
        source = TargetProfileSource.USER_IMPORT,
        status = TargetProfileStatus.DRAFT,
        checksum = "checksum",
        definition = TargetProfileDefinition(
            target = TargetRegistrationDefinition(
                id = "sideproject-local",
                name = "SideProject",
                adapterType = "HTTP_TARGET",
                environment = TargetEnvironment.LOCAL,
                baseUrl = "http://127.0.0.1:18080",
                allowedOrigin = "http://127.0.0.1:18080",
                allowedCidrs = setOf("127.0.0.0/8"),
                healthPath = "/actuator/health",
                openApiPath = "/api-docs/product",
                sourceRepository = "sideproject",
                identityVerification = IdentityVerificationStatus.CONFIGURATION_ONLY,
                capabilities = setOf(TargetCapability.HEALTH, TargetCapability.HTTP_API),
                enabled = true,
            ),
        ),
        createdBy = "tester",
        createdAt = Instant.EPOCH,
    )

    private class RecordingTransport(private val response: TargetReadResponse) : TargetReadTransport {
        var uri: URI? = null
        var method: String? = null

        override fun send(
            target: RegisteredTarget,
            uri: URI,
            method: String,
            headers: Map<String, String>,
            body: ByteArray,
            timeout: Duration,
        ): TargetReadResponse {
            this.uri = uri
            this.method = method
            return response
        }
    }
}
