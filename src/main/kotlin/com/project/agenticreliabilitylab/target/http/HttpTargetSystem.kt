package com.project.agenticreliabilitylab.target.http

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetHealthStatus
import com.project.agenticreliabilitylab.target.domain.TargetIdentity
import com.project.agenticreliabilitylab.target.domain.TargetSystem
import com.project.agenticreliabilitylab.target.domain.TargetSystemHealth
import com.project.agenticreliabilitylab.target.infrastructure.TargetHttpProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.util.concurrent.TimeUnit

@Component
class HttpTargetSystem(
    httpProperties: TargetHttpProperties,
    private val targetHttpTransport: PinnedTargetHttpTransport,
    private val clock: Clock,
) : TargetSystem {
    override val adapterType: String = ADAPTER_TYPE

    private val requestTimeout = maxOf(httpProperties.connectTimeout, httpProperties.readTimeout)

    override fun identity(target: RegisteredTarget): TargetIdentity = TargetIdentity(
        targetId = target.id,
        name = target.name,
        adapterType = target.adapterType,
        environment = target.environment,
        sourceRepository = target.sourceRepository,
        verificationStatus = target.identityVerification,
        observedAt = clock.instant(),
    )

    override fun capabilities(target: RegisteredTarget): Set<TargetCapability> = target.capabilities

    override fun health(target: RegisteredTarget): TargetSystemHealth {
        require(target.adapterType == adapterType) {
            "Target '${target.id}' is configured for adapter '${target.adapterType}'"
        }
        val healthUri = target.healthUri()
        require(healthUri.normalizedOrigin() == target.allowedOrigin.normalizedOrigin()) {
            "Target health URI is outside the registered allowed origin"
        }

        val startedAt = System.nanoTime()
        val observedAt = clock.instant()
        return try {
            val response = targetHttpTransport.send(target, healthUri, "GET", emptyMap(), ByteArray(0), requestTimeout)
            val isUp = response.statusCode in 200..299
            TargetSystemHealth(
                targetId = target.id,
                status = if (isUp) TargetHealthStatus.UP else TargetHealthStatus.DOWN,
                httpStatus = response.statusCode,
                latencyMs = startedAt.elapsedMs(),
                observedAt = observedAt,
                message = if (isUp) "Target health endpoint is reachable" else "Target health endpoint returned a non-success status",
            )
        } catch (_: Exception) {
            TargetSystemHealth(
                targetId = target.id,
                status = TargetHealthStatus.UNREACHABLE,
                httpStatus = null,
                latencyMs = startedAt.elapsedMs(),
                observedAt = observedAt,
                message = "Target health endpoint is unreachable",
            )
        }
    }

    private fun Long.elapsedMs(): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this)

    private fun URI.normalizedOrigin(): String {
        val effectivePort = when {
            port >= 0 -> port
            scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort"
    }

    private companion object {
        const val ADAPTER_TYPE = "HTTP_TARGET"
    }
}
