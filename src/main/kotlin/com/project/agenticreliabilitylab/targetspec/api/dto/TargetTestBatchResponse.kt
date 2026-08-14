package com.project.agenticreliabilitylab.targetspec.api.dto

import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchStatus
import java.time.Instant

data class TargetTestBatchResponse(
    val id: String,
    val targetSystemId: String,
    val status: TargetTestBatchStatus,
    val approvedAt: Instant?,
    val approvedBy: String?,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val failureMessage: String?,
    val items: List<TargetTestBatchItemResponse>,
) {
    companion object {
        fun from(batch: TargetTestBatchRecord, items: List<TargetTestBatchItemRecord>) = TargetTestBatchResponse(
            id = batch.id.toString(), targetSystemId = batch.targetSystemId, status = batch.status,
            approvedAt = batch.approvedAt,
            approvedBy = batch.approvedBy,
            queuedAt = batch.queuedAt,
            startedAt = batch.startedAt,
            completedAt = batch.completedAt, failureMessage = batch.failureMessage,
            items = items.map(TargetTestBatchItemResponse::from),
        )
    }
}
