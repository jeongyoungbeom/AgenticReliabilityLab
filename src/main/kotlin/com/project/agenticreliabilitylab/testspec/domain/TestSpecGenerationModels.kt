package com.project.agenticreliabilitylab.testspec.domain

import java.time.Instant
import java.util.UUID

/** Lifecycle of one model-proposal generation run. Mirrors the async job pattern used elsewhere in the project. */
enum class TestSpecGenerationRunStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
}

/** Whether one proposed specification survived the shared `TestSpecValidator` every specification passes. */
enum class TestSpecGenerationCandidateOutcome {
    ACCEPTED,
    REJECTED,
}

data class TestSpecGenerationRunRecord(
    val id: UUID,
    val targetSystemId: String,
    val knowledgeSnapshotId: UUID,
    val profileVersionId: UUID,
    val idempotencyKey: String,
    val configurationHash: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputBundleJson: String,
    val inputChecksum: String,
    val status: TestSpecGenerationRunStatus,
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

/**
 * One specification the model proposed.
 *
 * [specificationId] is set only when the candidate reached [TestSpecGenerationCandidateOutcome.ACCEPTED] and was
 * promoted into a real, storable specification through the same validator every other specification passes. A
 * rejected candidate keeps its [documentJson] and [rejectionReason] instead of disappearing, so a reviewer can see
 * what the model tried and why it did not survive.
 */
data class TestSpecGenerationCandidateRecord(
    val id: UUID,
    val runId: UUID,
    val ordinal: Int,
    val outcome: TestSpecGenerationCandidateOutcome,
    val specKey: String,
    val title: String,
    val documentJson: String,
    val rejectionReason: String?,
    val specificationId: UUID?,
)

data class TestSpecGenerationRunDetails(
    val run: TestSpecGenerationRunRecord,
    val candidates: List<TestSpecGenerationCandidateRecord>,
)
