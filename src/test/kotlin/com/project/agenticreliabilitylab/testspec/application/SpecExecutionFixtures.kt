package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.NetworkCidr
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.domain.TargetReadResponse
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthProvider
import com.project.agenticreliabilitylab.testspec.application.port.SpecExecutionSettings
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** One request that actually left ARL, kept so a test can assert what the Target would have seen. */
data class SentRequest(
    val uri: URI,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
)

/** A stand-in Target that answers from a handler and remembers everything it was asked. */
class RecordingTransport(
    private val handler: (SentRequest) -> TargetReadResponse,
) : TargetReadTransport {
    val requests: MutableList<SentRequest> = CopyOnWriteArrayList()

    override fun send(
        target: RegisteredTarget,
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeout: Duration,
    ): TargetReadResponse {
        val request = SentRequest(uri, method, headers, String(body, StandardCharsets.UTF_8))
        requests.add(request)
        return handler(request)
    }
}

fun jsonResponse(status: Int, body: String): TargetReadResponse =
    TargetReadResponse(status, body.toByteArray(StandardCharsets.UTF_8))

fun testTarget(): RegisteredTarget {
    val origin = URI("http://127.0.0.1:18080")
    return RegisteredTarget(
        id = "sideproject",
        name = "Side Project",
        adapterType = "HTTP_TARGET",
        environment = TargetEnvironment.LOCAL,
        baseUri = origin,
        allowedOrigin = origin,
        allowedNetworkCidrs = linkedSetOf(NetworkCidr.parse("127.0.0.0/8")),
        healthPath = "/actuator/health",
        sourceRepository = "sideproject",
        identityVerification = IdentityVerificationStatus.CONFIGURATION_ONLY,
        capabilities = setOf(TargetCapability.HEALTH, TargetCapability.HTTP_API),
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

class FixedSpecExecutionSettings(
    override val requestTimeout: Duration = Duration.ofSeconds(2),
    override val maxObservationWait: Duration = Duration.ofSeconds(2),
) : SpecExecutionSettings

/** Stands in for the Runner's secret store. The value never leaves this object except as a header. */
class StubAuthProvider(private val headers: Map<String, Map<String, String>>) : SpecAuthProvider {
    override fun headersFor(targetSystemId: String, authProfile: String): Map<String, String> =
        headers[authProfile] ?: emptyMap()
}
