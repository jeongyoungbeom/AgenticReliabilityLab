package com.project.agenticreliabilitylab.campaign.api.dto

import com.project.agenticreliabilitylab.campaign.domain.CampaignRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignRunStatus
import java.time.Instant

data class CampaignRunResponse(
    val id: String,
    val targetSystem: String,
    val repeatCount: Int,
    val status: CampaignRunStatus,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    companion object {
        fun from(run: CampaignRunRecord) = CampaignRunResponse(
            id = run.id.toString(),
            targetSystem = run.targetSystemId,
            repeatCount = run.repeatCount,
            status = run.status,
            createdAt = run.createdAt,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
        )
    }
}
