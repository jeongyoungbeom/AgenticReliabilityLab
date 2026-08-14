package com.project.agenticreliabilitylab.campaign.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.campaign.application.port.CampaignRunStore
import com.project.agenticreliabilitylab.campaign.domain.CampaignRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignRunStatus
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepStatus
import com.project.agenticreliabilitylab.campaign.infrastructure.sql.CampaignSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // Campaign creation, sequencing, leases, and completion share one aggregate.
class JdbcCampaignRepository(
    private val jdbcClient: JdbcClient,
    private val identifierGenerator: IdentifierGenerator,
) : CampaignRunStore {
    override fun findRun(id: UUID): CampaignRunRecord? =
        jdbcClient.sql(CampaignSql.FIND_RUN_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toCampaignRun() }
            .optional().orElse(null)

    override fun findByTargetAndIdempotency(targetSystemId: String, idempotencyKey: String): CampaignRunRecord? =
        jdbcClient.sql(CampaignSql.FIND_RUN_BY_TARGET_AND_IDEMPOTENCY)
            .params(mapOf("targetSystemId" to targetSystemId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toCampaignRun() }
            .optional().orElse(null)

    override fun findSteps(campaignRunId: UUID): List<CampaignStepRunRecord> =
        jdbcClient.sql(CampaignSql.FIND_STEPS).param("campaignRunId", campaignRunId)
            .query { resultSet, _ -> resultSet.toStep() }
            .list()

    @Transactional
    override fun createRun(
        targetSystemId: String,
        idempotencyKey: String,
        parametersJson: String,
        repeatCount: Int,
        now: Instant,
    ): CampaignRunRecord {
        val campaignId = identifierGenerator.next()
        jdbcClient.sql(CampaignSql.INSERT_RUN).params(
            mapOf(
                "id" to campaignId,
                "definitionId" to DEFINITION_ID,
                "targetSystemId" to targetSystemId,
                "idempotencyKey" to idempotencyKey,
                "parametersJson" to parametersJson,
                "repeatCount" to repeatCount,
                "status" to CampaignRunStatus.RUNNING.name,
                "createdAt" to Timestamp.from(now),
                "startedAt" to Timestamp.from(now),
            ),
        ).update()
        (1..repeatCount).forEach { sequence ->
            jdbcClient.sql(CampaignSql.INSERT_STEP).params(
                mapOf(
                    "id" to identifierGenerator.next(),
                    "campaignRunId" to campaignId,
                    "stepKey" to "stock-concurrency-$sequence",
                    "definitionVersion" to EXPERIMENT_DEFINITION_VERSION,
                    "sequenceNumber" to sequence,
                    "status" to CampaignStepStatus.QUEUED.name,
                    "queuedAt" to Timestamp.from(now),
                ),
            ).update()
        }
        return findRun(campaignId) ?: error("Campaign '$campaignId' was not persisted")
    }

    @Suppress("ReturnCount") // The explicit exits preserve lease-claim safety without an extra query.
    override fun claimNextQueuedStep(
        campaignRunId: UUID,
        workerId: String,
        now: Instant,
        leaseExpiresAt: Instant,
    ): CampaignStepRunRecord? {
        val campaignExists = jdbcClient.sql(CampaignSql.LOCK_RUN).param("campaignRunId", campaignRunId)
            .query(String::class.java)
            .optional()
            .orElse(null)
        if (campaignExists == null) return null

        val candidate = jdbcClient.sql(CampaignSql.FIND_NEXT_QUEUED_STEP)
            .params(mapOf("campaignRunId" to campaignRunId, "status" to CampaignStepStatus.QUEUED.name))
            .query { resultSet, _ -> resultSet.toStep() }
            .optional().orElse(null) ?: return null

        val updated = jdbcClient.sql(CampaignSql.CLAIM_NEXT_QUEUED_STEP).params(
            mapOf(
                "id" to candidate.id,
                "campaignRunId" to campaignRunId,
                "running" to CampaignStepStatus.RUNNING.name,
                "queued" to CampaignStepStatus.QUEUED.name,
                "startedAt" to Timestamp.from(now),
                "workerId" to workerId,
                "leaseExpiresAt" to Timestamp.from(leaseExpiresAt),
            ),
        ).update()
        return if (updated == 1) findSteps(campaignRunId).first { it.id == candidate.id } else null
    }

    override fun attachExperimentRun(step: CampaignStepRunRecord, experimentRunId: UUID): Boolean =
        jdbcClient.sql(CampaignSql.ATTACH_EXPERIMENT).params(
            mapOf(
                "experimentRunId" to experimentRunId,
                "stepId" to step.id,
                "campaignRunId" to step.campaignRunId,
                "running" to CampaignStepStatus.RUNNING.name,
                "leaseOwner" to step.leaseOwner,
            ),
        ).update() == 1

    override fun takeOverExpiredRunningStep(
        campaignRunId: UUID,
        workerId: String,
        now: Instant,
        leaseExpiresAt: Instant,
    ): CampaignStepRunRecord? {
        val candidate = jdbcClient.sql(CampaignSql.FIND_EXPIRED_STEP).params(
            mapOf(
                "campaignRunId" to campaignRunId,
                "running" to CampaignStepStatus.RUNNING.name,
                "now" to Timestamp.from(now),
            ),
        ).query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }.optional().orElse(null) ?: return null

        val updated = jdbcClient.sql(CampaignSql.TAKE_OVER_EXPIRED_STEP).params(
            mapOf(
                "id" to candidate,
                "running" to CampaignStepStatus.RUNNING.name,
                "workerId" to workerId,
                "leaseExpiresAt" to Timestamp.from(leaseExpiresAt),
                "now" to Timestamp.from(now),
            ),
        ).update()
        return if (updated == 1) findSteps(campaignRunId).first { it.id == candidate } else null
    }

    override fun renewStepLease(
        step: CampaignStepRunRecord,
        now: Instant,
        leaseExpiresAt: Instant,
    ): CampaignStepRunRecord? {
        val updated = jdbcClient.sql(CampaignSql.RENEW_STEP_LEASE).params(
            mapOf(
                "id" to step.id,
                "running" to CampaignStepStatus.RUNNING.name,
                "leaseOwner" to step.leaseOwner,
                "fencingToken" to step.fencingToken,
                "now" to Timestamp.from(now),
                "leaseExpiresAt" to Timestamp.from(leaseExpiresAt),
            ),
        ).update()
        return if (updated == 1) findSteps(step.campaignRunId).first { it.id == step.id } else null
    }

    override fun completeStep(
        step: CampaignStepRunRecord,
        status: CampaignStepStatus,
        now: Instant,
        failureCode: String?,
        failureMessage: String?,
    ): Boolean {
        return jdbcClient.sql(CampaignSql.COMPLETE_STEP).params(
            mapOf(
                "id" to step.id,
                "status" to status.name,
                "completedAt" to Timestamp.from(now),
                "failureCode" to failureCode,
                "failureMessage" to failureMessage?.take(1000),
                "running" to CampaignStepStatus.RUNNING.name,
                "leaseOwner" to step.leaseOwner,
                "fencingToken" to step.fencingToken,
            ),
        ).update() == 1
    }

    override fun completeRun(campaignRunId: UUID, status: CampaignRunStatus, now: Instant) {
        jdbcClient.sql(CampaignSql.COMPLETE_RUN).params(
            mapOf(
                "id" to campaignRunId,
                "status" to status.name,
                "running" to CampaignRunStatus.RUNNING.name,
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun completeRunIfAllStepsCompleted(campaignRunId: UUID, now: Instant): Boolean =
        jdbcClient.sql(CampaignSql.COMPLETE_RUN_IF_ALL_STEPS_COMPLETED).params(
            mapOf(
                "id" to campaignRunId,
                "completed" to CampaignRunStatus.COMPLETED.name,
                "running" to CampaignRunStatus.RUNNING.name,
                "stepCompleted" to CampaignStepStatus.COMPLETED.name,
                "completedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    override fun findRunningRuns(): List<CampaignRunRecord> =
        jdbcClient.sql(CampaignSql.FIND_RUNNING_RUNS)
            .param("status", CampaignRunStatus.RUNNING.name)
            .query { resultSet, _ -> resultSet.toCampaignRun() }
            .list()

    private fun ResultSet.toCampaignRun(): CampaignRunRecord = CampaignRunRecord(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        idempotencyKey = getString("idempotency_key"),
        parametersJson = getString("parameters_json"),
        repeatCount = getInt("repeat_count"),
        status = CampaignRunStatus.valueOf(getString("status")),
        createdAt = getTimestamp("created_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

    private fun ResultSet.toStep(): CampaignStepRunRecord = CampaignStepRunRecord(
        id = getObject("id", UUID::class.java),
        campaignRunId = getObject("campaign_run_id", UUID::class.java),
        sequenceNumber = getInt("sequence_number"),
        status = CampaignStepStatus.valueOf(getString("status")),
        experimentRunId = getObject("experiment_run_id", UUID::class.java),
        leaseOwner = getString("lease_owner"),
        leaseExpiresAt = getTimestamp("lease_expires_at")?.toInstant(),
        fencingToken = getLong("fencing_token"),
        queuedAt = getTimestamp("queued_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        failureCode = getString("failure_code"),
        failureMessage = getString("failure_message"),
    )

    private companion object {
        const val DEFINITION_ID = "stock-concurrency-repeat-v1"
        const val EXPERIMENT_DEFINITION_VERSION = 1
    }
}
