package com.project.agenticreliabilitylab.targetprofile.api.dto

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition

data class TargetProfileValidationResponse(
    val targetSystemId: String,
    val targetName: String,
    val environment: String,
    val genericHttpEnabled: Boolean,
    val readOnlyOperationCount: Int,
    val experimentProfilePresent: Boolean,
) {
    companion object {
        fun from(definition: TargetProfileDefinition): TargetProfileValidationResponse =
            TargetProfileValidationResponse(
            targetSystemId = definition.target.id,
            targetName = definition.target.name,
            environment = definition.target.environment.name,
            genericHttpEnabled = definition.genericHttp?.executionEnabled ?: false,
            readOnlyOperationCount = definition.genericHttp?.readOnlyOperations?.size ?: 0,
            experimentProfilePresent = definition.experiment != null,
        )
    }
}
