package com.project.agenticreliabilitylab.target.api.dto

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment

data class TargetSummaryResponse(
    val id: String,
    val name: String,
    val adapterType: String,
    val environment: TargetEnvironment,
    val baseUrl: String,
    val healthPath: String,
    val identityVerification: IdentityVerificationStatus,
    val capabilities: Set<TargetCapability>,
    val enabled: Boolean,
) {
    companion object {
        fun from(target: RegisteredTarget) = TargetSummaryResponse(
            id = target.id, name = target.name, adapterType = target.adapterType, environment = target.environment,
            baseUrl = target.baseUri.toString(), healthPath = target.healthPath,
            identityVerification = target.identityVerification,
            capabilities = target.capabilities,
            enabled = target.enabled,
        )
    }
}
