package com.project.agenticreliabilitylab.campaign.api.dto

import com.project.agenticreliabilitylab.campaign.domain.CampaignStepRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepStatus
import java.time.Instant

data class CampaignStepRunResponse(
    val sequenceNumber: Int,
    val status: CampaignStepStatus,
    val experimentRunId: String?,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val failureCode: String?,
    val failureMessage: String?,
) {
    companion object {
        fun from(step: CampaignStepRunRecord) = CampaignStepRunResponse(
            sequenceNumber = step.sequenceNumber,
            status = step.status,
            experimentRunId = step.experimentRunId?.toString(),
            queuedAt = step.queuedAt,
            startedAt = step.startedAt,
            completedAt = step.completedAt,
            failureCode = step.failureCode,
            failureMessage = step.failureMessage,
        )
    }
}
