package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.domain.TestSpecMisjudgmentReportRecord
import com.project.agenticreliabilitylab.testspec.domain.TestSpecMisjudgmentReportStatus
import java.time.Instant
import java.util.UUID

data class TestSpecMisjudgmentReportResponse(
    val id: UUID,
    val targetSystemId: String,
    val specificationId: UUID,
    val runId: UUID,
    val trialNumber: Int,
    val invariantId: String,
    val reason: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val status: TestSpecMisjudgmentReportStatus,
    val draftedCondition: String?,
    val draftedDescription: String?,
    val resultingSpecificationId: UUID?,
    val rejectionReason: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    companion object {
        fun from(record: TestSpecMisjudgmentReportRecord) = TestSpecMisjudgmentReportResponse(
            id = record.id,
            targetSystemId = record.targetSystemId,
            specificationId = record.specificationId,
            runId = record.runId,
            trialNumber = record.trialNumber,
            invariantId = record.invariantId,
            reason = record.reason,
            modelKey = record.modelKey,
            modelId = record.modelId,
            promptVersion = record.promptVersion,
            status = record.status,
            draftedCondition = record.draftedCondition,
            draftedDescription = record.draftedDescription,
            resultingSpecificationId = record.resultingSpecificationId,
            rejectionReason = record.rejectionReason,
            promptTokenCount = record.promptTokenCount,
            completionTokenCount = record.completionTokenCount,
            durationMillis = record.durationMillis,
            failureCode = record.failureCode,
            failureMessage = record.failureMessage,
            requestedAt = record.requestedAt,
            startedAt = record.startedAt,
            completedAt = record.completedAt,
        )
    }
}
