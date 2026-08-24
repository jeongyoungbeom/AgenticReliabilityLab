package com.project.agenticreliabilitylab.testspec.application.port

import com.project.agenticreliabilitylab.testspec.domain.TestSpecMisjudgmentReportRecord
import com.project.agenticreliabilitylab.testspec.domain.TestSpecMisjudgmentReportStatus
import java.time.Instant
import java.util.UUID

/** Persistence boundary for reviewer-reported misjudgments and the exception drafts they produce. */
interface TestSpecMisjudgmentReportStore {
    fun create(command: NewTestSpecMisjudgmentReport)
    fun findById(id: UUID): TestSpecMisjudgmentReportRecord?
    fun findByTargetAndIdempotencyKey(
        targetSystemId: String,
        idempotencyKey: String,
    ): TestSpecMisjudgmentReportRecord?
    fun claim(id: UUID, now: Instant): Boolean
    fun complete(id: UUID, completion: TestSpecMisjudgmentCompletion, now: Instant)
    fun fail(id: UUID, code: String, message: String, now: Instant)
    fun findRequestedIds(): List<UUID>
    fun findRunningIds(): List<UUID>
}

data class NewTestSpecMisjudgmentReport(
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
    val requestedBy: String,
    val requestedCorrelationId: String,
    val requestedAt: Instant,
)

data class TestSpecMisjudgmentCompletion(
    val status: TestSpecMisjudgmentReportStatus,
    val draftedCondition: String,
    val draftedDescription: String,
    val resultingSpecificationId: UUID?,
    val rejectionReason: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
)
