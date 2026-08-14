package com.project.agenticreliabilitylab.targetspec.application.port

import com.project.agenticreliabilitylab.analysis.application.port.TargetTestBatchAnalysisEvidenceSource
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import java.time.Instant
import java.util.UUID

/** Persistence boundary for generic Target HTTP test batch state. */
@Suppress("TooManyFunctions") // A batch aggregate owns approval, item execution, completion, and recovery.
interface TargetTestBatchStore : TargetTestBatchAnalysisEvidenceSource {
    fun findById(id: UUID): TargetTestBatchRecord?
    fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): TargetTestBatchRecord?
    fun findItems(batchId: UUID): List<TargetTestBatchItemRecord>
    fun create(batch: NewTargetTestBatch)
    fun approve(batchId: UUID, actor: String, correlationId: String, now: Instant): Boolean
    fun cancelPendingApproval(batchId: UUID, now: Instant, message: String): Boolean
    fun claimForExecution(batchId: UUID, now: Instant): Boolean
    fun claimItem(itemId: UUID, now: Instant): Boolean
    fun completeItem(
        itemId: UUID,
        status: TargetTestBatchItemStatus,
        httpStatus: Int?,
        latencyMs: Long?,
        resultJson: String?,
        failureMessage: String?,
        now: Instant,
    )
    fun completeBatch(batchId: UUID, status: TargetTestBatchStatus, failureMessage: String?, now: Instant)
    fun markSchedulingFailed(batchId: UUID, now: Instant, message: String)
    fun markRecoveryRequired(batchId: UUID, now: Instant, message: String)
    fun findApprovedBatchIds(): List<UUID>
    fun findRunningBatchIds(): List<UUID>
}

data class NewTargetTestBatch(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val idempotencyKey: String,
    val requestHash: String,
    val candidates: List<TargetTestCandidate>,
    val queuedAt: Instant,
)
