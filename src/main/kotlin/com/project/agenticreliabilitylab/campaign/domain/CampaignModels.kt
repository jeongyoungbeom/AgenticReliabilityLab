package com.project.agenticreliabilitylab.campaign.domain

import java.time.Instant
import java.util.UUID

enum class CampaignRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    RECOVERY_REQUIRED,
}

enum class CampaignStepStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    RECOVERY_REQUIRED,
}

data class CampaignRunRecord(
    val id: UUID,
    val targetSystemId: String,
    val idempotencyKey: String,
    val parametersJson: String,
    val repeatCount: Int,
    val status: CampaignRunStatus,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class CampaignStepRunRecord(
    val id: UUID,
    val campaignRunId: UUID,
    val sequenceNumber: Int,
    val status: CampaignStepStatus,
    val experimentRunId: UUID?,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val fencingToken: Long,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val failureCode: String?,
    val failureMessage: String?,
)
