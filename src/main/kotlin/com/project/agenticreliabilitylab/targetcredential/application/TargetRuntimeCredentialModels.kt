package com.project.agenticreliabilitylab.targetcredential.application


enum class TargetCredentialRole {
    SELLER,
    BUYER,
    HARNESS,
    ;

    val profileName: String = name.lowercase()
}

data class TargetRuntimeCredentialStatus(
    val targetSystemId: String,
    /** Opaque browser-memory capability. It is not a Target credential and ends on explicit clear or ARL restart. */
    val credentialSessionId: String,
    val storedRoles: Set<String>,
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
