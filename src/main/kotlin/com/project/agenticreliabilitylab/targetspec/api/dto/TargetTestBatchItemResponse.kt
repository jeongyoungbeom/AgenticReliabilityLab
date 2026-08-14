package com.project.agenticreliabilitylab.targetspec.api.dto

import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidateKind
import java.time.Instant

data class TargetTestBatchItemResponse(
    val id: String,
    val candidateId: String,
    val sequenceNumber: Int,
    val kind: TargetTestCandidateKind,
    val title: String,
    val method: String,
    val path: String,
    val expectedStatusCodes: Set<Int>,
    val status: TargetTestBatchItemStatus,
    val httpStatus: Int?,
    val latencyMs: Long?,
    val evidence: String?,
    val failureMessage: String?,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    companion object {
        fun from(item: TargetTestBatchItemRecord) = TargetTestBatchItemResponse(
            id = item.id.toString(),
            candidateId = item.candidateId,
            sequenceNumber = item.sequenceNumber,
            kind = item.kind,
            title = item.title,
            method = item.method,
            path = item.path,
            expectedStatusCodes = item.expectedStatusCodes,
            status = item.status,
            httpStatus = item.httpStatus,
            latencyMs = item.latencyMs,
            evidence = item.resultJson,
            failureMessage = item.failureMessage,
            startedAt = item.startedAt,
            completedAt = item.completedAt,
        )
    }
}
