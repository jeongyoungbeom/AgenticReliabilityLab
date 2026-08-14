package com.project.agenticreliabilitylab.targetspec.api.dto

import com.project.agenticreliabilitylab.targetspec.application.model.FailureInjectionPlanDetails
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanStatus
import java.time.Instant

data class FailureInjectionPlanResponse(
    val id: String,
    val targetSystemId: String,
    val status: FailureInjectionPlanStatus,
    val approvedAt: Instant?,
    val approvedBy: String?,
    val createdAt: Instant,
    val executionAvailable: Boolean,
    val items: List<FailureInjectionPlanItemResponse>,
) {
    companion object {
        fun from(details: FailureInjectionPlanDetails) = FailureInjectionPlanResponse(
            id = details.plan.id.toString(),
            targetSystemId = details.plan.targetSystemId,
            status = details.plan.status,
            approvedAt = details.plan.approvedAt,
            approvedBy = details.plan.approvedBy,
            createdAt = details.plan.createdAt,
            executionAvailable = false,
            items = details.items.map {
                FailureInjectionPlanItemResponse(
                    it.sequenceNumber,
                    it.candidateId,
                    it.type,
                    it.risk,
                    it.title,
                    it.recoveryExpectation,
                )
            },
        )
    }
}
