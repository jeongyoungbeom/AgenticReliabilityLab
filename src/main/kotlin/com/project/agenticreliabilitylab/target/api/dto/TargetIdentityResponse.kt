package com.project.agenticreliabilitylab.target.api.dto

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.domain.TargetIdentity
import java.time.Instant

data class TargetIdentityResponse(
    val targetId: String,
    val name: String,
    val adapterType: String,
    val environment: TargetEnvironment,
    val verificationStatus: IdentityVerificationStatus,
    val observedAt: Instant,
) {
    companion object {
        fun from(identity: TargetIdentity) = TargetIdentityResponse(
            targetId = identity.targetId, name = identity.name, adapterType = identity.adapterType,
            environment = identity.environment, verificationStatus = identity.verificationStatus,
            observedAt = identity.observedAt,
        )
    }
}
