package com.project.agenticreliabilitylab.target.domain

import java.net.URI
import java.time.Instant

enum class TargetEnvironment {
    LOCAL,
    TEST,
    STAGING,
    PRODUCTION,
}

enum class TargetCapability {
    HEALTH,
    HTTP_API,
    ENVIRONMENT_IDENTITY,
    STOCK_CONCURRENCY,
}

enum class IdentityVerificationStatus {
    VERIFIED,
    CONFIGURATION_ONLY,
    UNVERIFIED,
}

enum class TargetHealthStatus {
    UP,
    DOWN,
    UNREACHABLE,
}

data class RegisteredTarget(
    val id: String,
    val name: String,
    val adapterType: String,
    val environment: TargetEnvironment,
    val baseUri: URI,
    val allowedOrigin: URI,
    val allowedNetworkCidrs: Set<NetworkCidr>,
    val healthPath: String,
    val sourceRepository: String,
    val identityVerification: IdentityVerificationStatus,
    val capabilities: Set<TargetCapability>,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun healthUri(): URI = baseUri.resolve(healthPath)
}

data class TargetIdentity(
    val targetId: String,
    val name: String,
    val adapterType: String,
    val environment: TargetEnvironment,
    val sourceRepository: String,
    val verificationStatus: IdentityVerificationStatus,
    val observedAt: Instant,
)

data class TargetSystemHealth(
    val targetId: String,
    val status: TargetHealthStatus,
    val httpStatus: Int?,
    val latencyMs: Long,
    val observedAt: Instant,
    val message: String,
)

interface TargetSystem {
    val adapterType: String

    fun identity(target: RegisteredTarget): TargetIdentity

    fun capabilities(target: RegisteredTarget): Set<TargetCapability>

    fun health(target: RegisteredTarget): TargetSystemHealth
}
