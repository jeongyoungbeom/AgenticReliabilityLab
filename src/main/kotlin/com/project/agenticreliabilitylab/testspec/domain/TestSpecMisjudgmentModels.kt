package com.project.agenticreliabilitylab.testspec.domain

import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of one misjudgment report. Mirrors the async job pattern [TestSpecGenerationRunStatus] uses, but a
 * report has exactly one outcome instead of many candidates, so the outcome is folded into the status itself.
 */
enum class TestSpecMisjudgmentReportStatus {
    REQUESTED,
    RUNNING,

    /**
     * The validator accepted the drafted exception; [TestSpecMisjudgmentReportRecord.resultingSpecificationId]
     * is set.
     */
    DRAFTED,

    /**
     * The validator rejected the drafted exception; [TestSpecMisjudgmentReportRecord.rejectionReason] is set.
     */
    REJECTED,
    FAILED,
}

/**
 * A reviewer's claim that one invariant's `VIOLATED` verdict on one run was wrong.
 *
 * This is the only place a misjudgment is recorded as such - the resulting specification version, once approved,
 * carries only the exception itself, not the fact that it originated from a report (TEST_SPEC.md 12).
 */
data class TestSpecMisjudgmentReportRecord(
    val id: UUID,
    val targetSystemId: String,
    val specificationId: UUID,
    val runId: UUID,
    val trialNumber: Int,
    val invariantId: String,
    val reason: String,
    val idempotencyKey: String,
    val requestHash: String,
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
    val requestedBy: String,
    val requestedCorrelationId: String,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)
