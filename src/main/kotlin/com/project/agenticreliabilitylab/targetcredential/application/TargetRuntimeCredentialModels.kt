package com.project.agenticreliabilitylab.targetcredential.application

import java.time.Instant

enum class TargetCredentialRole {
    SELLER,
    BUYER,
    HARNESS,
    ;

    val profileName: String = name.lowercase()
}

data class TargetRuntimeCredentialStatus(
    val targetSystemId: String,
    /** Opaque, short-lived capability kept only in the current browser memory. It is not a Target credential. */
    val credentialSessionId: String,
    val storedRoles: Set<String>,
    val expiresAt: Instant?,
)

enum class TargetCredentialPreflightStatus {
    READY,
    TARGET_CREDENTIAL_MISSING,
    TARGET_CREDENTIAL_EXPIRED,
    PREFLIGHT_NOT_CONFIGURED,
    TARGET_PREFLIGHT_FAILED,
    TARGET_UNREACHABLE,
}

data class TargetCredentialPreflightResult(
    val role: String,
    val status: TargetCredentialPreflightStatus,
    val method: String?,
    val path: String?,
    val httpStatus: Int?,
)
