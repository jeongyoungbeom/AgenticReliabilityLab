package com.project.agenticreliabilitylab.testcatalog.api.dto

import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBinding

data class ExecutionBindingResponse(
    val kind: String,
    val targetTestCandidateIds: List<String>,
    val experimentType: String?,
    val requiredCapability: String?,
    val unresolvedReason: String?,
    val unresolvedDetail: String?,
) {
    companion object {
        fun from(binding: ExecutionBinding): ExecutionBindingResponse = ExecutionBindingResponse(
            kind = binding.kind.name,
            targetTestCandidateIds = binding.targetTestCandidateIds,
            experimentType = binding.experimentType?.name,
            requiredCapability = binding.requiredCapability,
            unresolvedReason = binding.unresolvedReason?.name,
            unresolvedDetail = binding.unresolvedDetail,
        )
    }
}
