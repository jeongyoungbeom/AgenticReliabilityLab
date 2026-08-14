package com.project.agenticreliabilitylab.targetspec.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.targetspec.application.port.NewTargetTestBatch
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestBatchStore
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidateKind
import com.project.agenticreliabilitylab.targetspec.infrastructure.sql.TargetTestBatchSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Suppress("TooManyFunctions") // Persistence methods mirror the batch and batch-item tables.
@Repository
class JdbcTargetTestBatchRepository(
    private val jdbcClient: JdbcClient,
    private val identifierGenerator: IdentifierGenerator,
) : TargetTestBatchStore {
    override fun findTargetTestBatch(id: UUID): TargetTestBatchRecord? = findById(id)

    override fun findById(id: UUID): TargetTestBatchRecord? =
        jdbcClient.sql(TargetTestBatchSql.FIND_BATCH_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toBatch() }
            .optional()
            .orElse(null)

    override fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): TargetTestBatchRecord? =
        jdbcClient.sql(TargetTestBatchSql.FIND_BATCH_BY_TARGET_AND_IDEMPOTENCY_KEY)
            .params(mapOf("targetSystemId" to targetSystemId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toBatch() }
            .optional()
            .orElse(null)

    override fun findTargetTestBatchItems(batchId: UUID): List<TargetTestBatchItemRecord> = findItems(batchId)

    override fun findItems(batchId: UUID): List<TargetTestBatchItemRecord> =
        jdbcClient.sql(TargetTestBatchSql.FIND_ITEMS_BY_BATCH_ID).param("batchId", batchId)
            .query { resultSet, _ -> resultSet.toItem() }
            .list()

    @Transactional
    override fun create(batch: NewTargetTestBatch) {
        jdbcClient.sql(TargetTestBatchSql.INSERT_BATCH).params(
            mapOf(
                "id" to batch.id,
                "targetSystemId" to batch.targetSystemId,
                "profileVersionId" to batch.profileVersionId,
                "idempotencyKey" to batch.idempotencyKey,
                "requestHash" to batch.requestHash,
                "status" to TargetTestBatchStatus.PENDING_APPROVAL.name,
                "queuedAt" to Timestamp.from(batch.queuedAt),
            ),
        ).update()

        batch.candidates.forEachIndexed { index, candidate ->
            jdbcClient.sql(TargetTestBatchSql.INSERT_ITEM).params(
                mapOf(
                    "id" to identifierGenerator.next(),
                    "batchId" to batch.id,
                    "candidateId" to candidate.id,
                    "sequenceNumber" to index + 1,
                    "candidateKind" to candidate.kind.name,
                    "title" to candidate.title,
                    "method" to candidate.method,
                    "path" to candidate.path,
                    "expectedStatusCodes" to candidate.expectedStatusCodes.sorted().joinToString(","),
                    "timeoutMillis" to candidate.timeout.toMillis(),
                    "status" to TargetTestBatchItemStatus.PENDING.name,
                ),
            ).update()
        }
    }

    override fun approve(batchId: UUID, actor: String, correlationId: String, now: Instant): Boolean =
        jdbcClient.sql(TargetTestBatchSql.APPROVE).params(
            mapOf(
                "id" to batchId,
                "pendingApproval" to TargetTestBatchStatus.PENDING_APPROVAL.name,
                "approved" to TargetTestBatchStatus.APPROVED.name,
                "approvedAt" to Timestamp.from(now),
                "approvedBy" to actor,
                "approvalCorrelationId" to correlationId,
            ),
        ).update() == 1

    override fun cancelPendingApproval(batchId: UUID, now: Instant, message: String): Boolean =
        jdbcClient.sql(TargetTestBatchSql.CANCEL_PENDING_APPROVAL).params(
            mapOf(
                "id" to batchId,
                "pendingApproval" to TargetTestBatchStatus.PENDING_APPROVAL.name,
                "cancelled" to TargetTestBatchStatus.CANCELLED.name,
                "message" to message.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    override fun claimForExecution(batchId: UUID, now: Instant): Boolean =
        jdbcClient.sql(TargetTestBatchSql.CLAIM_FOR_EXECUTION).params(
            mapOf(
                "id" to batchId,
                "approved" to TargetTestBatchStatus.APPROVED.name,
                "running" to TargetTestBatchStatus.RUNNING.name,
                "startedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    override fun claimItem(itemId: UUID, now: Instant): Boolean =
        jdbcClient.sql(TargetTestBatchSql.CLAIM_ITEM).params(
            mapOf(
                "id" to itemId,
                "pending" to TargetTestBatchItemStatus.PENDING.name,
                "running" to TargetTestBatchItemStatus.RUNNING.name,
                "startedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    override fun completeItem(
        itemId: UUID,
        status: TargetTestBatchItemStatus,
        httpStatus: Int?,
        latencyMs: Long?,
        resultJson: String?,
        failureMessage: String?,
        now: Instant,
    ) {
        jdbcClient.sql(TargetTestBatchSql.COMPLETE_ITEM).params(
            mapOf(
                "id" to itemId,
                "running" to TargetTestBatchItemStatus.RUNNING.name,
                "status" to status.name,
                "httpStatus" to httpStatus,
                "latencyMillis" to latencyMs,
                "resultJson" to resultJson,
                "failureMessage" to failureMessage?.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun completeBatch(batchId: UUID, status: TargetTestBatchStatus, failureMessage: String?, now: Instant) {
        jdbcClient.sql(TargetTestBatchSql.COMPLETE_BATCH).params(
            mapOf(
                "id" to batchId,
                "running" to TargetTestBatchStatus.RUNNING.name,
                "status" to status.name,
                "failureMessage" to failureMessage?.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun markSchedulingFailed(batchId: UUID, now: Instant, message: String) {
        jdbcClient.sql(TargetTestBatchSql.MARK_SCHEDULING_FAILED).params(
            mapOf(
                "id" to batchId,
                "approved" to TargetTestBatchStatus.APPROVED.name,
                "failed" to TargetTestBatchStatus.FAILED.name,
                "message" to message.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    @Transactional
    override fun markRecoveryRequired(batchId: UUID, now: Instant, message: String) {
        jdbcClient.sql(TargetTestBatchSql.BLOCK_RUNNING_ITEMS_FOR_RECOVERY).params(
            mapOf(
                "batchId" to batchId,
                "running" to TargetTestBatchItemStatus.RUNNING.name,
                "blocked" to TargetTestBatchItemStatus.BLOCKED.name,
                "message" to message.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
        jdbcClient.sql(TargetTestBatchSql.MARK_BATCH_RECOVERY_REQUIRED).params(
            mapOf(
                "id" to batchId,
                "running" to TargetTestBatchStatus.RUNNING.name,
                "recoveryRequired" to TargetTestBatchStatus.RECOVERY_REQUIRED.name,
                "message" to message.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun findApprovedBatchIds(): List<UUID> =
        jdbcClient.sql(TargetTestBatchSql.FIND_APPROVED_BATCH_IDS)
            .param("approved", TargetTestBatchStatus.APPROVED.name)
            .query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }.list()

    override fun findRunningBatchIds(): List<UUID> =
        jdbcClient.sql(TargetTestBatchSql.FIND_RUNNING_BATCH_IDS)
            .param("running", TargetTestBatchStatus.RUNNING.name)
            .query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }.list()

    private fun ResultSet.toBatch(): TargetTestBatchRecord = TargetTestBatchRecord(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        requestHash = getString("request_hash"),
        status = TargetTestBatchStatus.valueOf(getString("status")),
        approvedAt = getTimestamp("approved_at")?.toInstant(),
        approvedBy = getString("approved_by"),
        approvalCorrelationId = getString("approval_correlation_id"),
        queuedAt = getTimestamp("queued_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        failureMessage = getString("failure_message"),
    )

    private fun ResultSet.toItem(): TargetTestBatchItemRecord = TargetTestBatchItemRecord(
        id = getObject("id", UUID::class.java),
        batchId = getObject("batch_id", UUID::class.java),
        candidateId = getString("candidate_id"),
        sequenceNumber = getInt("sequence_number"),
        kind = TargetTestCandidateKind.valueOf(getString("candidate_kind")),
        title = getString("title"),
        method = getString("method"),
        path = getString("path"),
        expectedStatusCodes = getString("expected_status_codes").split(',').map(String::toInt).toSet(),
        timeout = Duration.ofMillis(getLong("timeout_millis")),
        status = TargetTestBatchItemStatus.valueOf(getString("status")),
        httpStatus = getObject("http_status") as Int?,
        latencyMs = getObject("latency_millis") as Long?,
        resultJson = getString("result_json"),
        failureMessage = getString("failure_message"),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

}
