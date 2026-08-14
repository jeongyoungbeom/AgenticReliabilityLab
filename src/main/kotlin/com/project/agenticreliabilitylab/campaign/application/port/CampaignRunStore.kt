package com.project.agenticreliabilitylab.campaign.application.port

import com.project.agenticreliabilitylab.campaign.domain.CampaignRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignRunStatus
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepStatus
import java.time.Instant
import java.util.UUID

/** Persistence boundary for campaign sequencing, leases and durable step results. */
@Suppress("TooManyFunctions") // A campaign aggregate owns creation, sequencing, leases, and completion.
interface CampaignRunStore : CampaignStepExperimentLinkStore {
    fun findRun(id: UUID): CampaignRunRecord?
    fun findByTargetAndIdempotency(targetSystemId: String, idempotencyKey: String): CampaignRunRecord?
    fun findSteps(campaignRunId: UUID): List<CampaignStepRunRecord>
    fun createRun(
        targetSystemId: String,
        idempotencyKey: String,
        parametersJson: String,
        repeatCount: Int,
        now: Instant,
    ): CampaignRunRecord
    fun claimNextQueuedStep(
        campaignRunId: UUID,
        workerId: String,
        now: Instant,
        leaseExpiresAt: Instant,
    ): CampaignStepRunRecord?
    fun takeOverExpiredRunningStep(
        campaignRunId: UUID,
        workerId: String,
        now: Instant,
        leaseExpiresAt: Instant,
    ): CampaignStepRunRecord?
    fun renewStepLease(step: CampaignStepRunRecord, now: Instant, leaseExpiresAt: Instant): CampaignStepRunRecord?
    fun completeStep(
        step: CampaignStepRunRecord,
        status: CampaignStepStatus,
        now: Instant,
        failureCode: String? = null,
        failureMessage: String? = null,
    ): Boolean
    fun completeRun(campaignRunId: UUID, status: CampaignRunStatus, now: Instant)
    fun completeRunIfAllStepsCompleted(campaignRunId: UUID, now: Instant): Boolean
    fun findRunningRuns(): List<CampaignRunRecord>
}
