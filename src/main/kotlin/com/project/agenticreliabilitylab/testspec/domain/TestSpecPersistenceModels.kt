package com.project.agenticreliabilitylab.testspec.domain

import java.time.Instant
import java.util.UUID

enum class TestSpecificationStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    SUPERSEDED,
}

data class StoredTestSpecification(
    val id: UUID,
    val targetSystemId: String,
    val specKey: String,
    val version: Int,
    val title: String,
    val profileVersionId: UUID,
    val source: SpecSource,
    val category: SpecCategory,
    val risk: SpecRisk,
    val status: TestSpecificationStatus,
    val documentJson: String,
    val checksum: String,
    val createdBy: String,
    val createdCorrelationId: String,
    val createdAt: Instant,
    val approvedBy: String? = null,
    val approvedCorrelationId: String? = null,
    val approvedAt: Instant? = null,
    val terminalReason: String? = null,
)

enum class TestSpecRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    RECOVERY_REQUIRED,
}

data class TestSpecRun(
    val id: UUID,
    val specificationId: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val status: TestSpecRunStatus,
    val idempotencyKey: String,
    val requestHash: String,
    val requestedTrials: Int,
    val createdBy: String,
    val createdCorrelationId: String,
    val createdAt: Instant,
    val resultOutcome: TrialOutcome? = null,
    val trialsRun: Int? = null,
    val trialsViolated: Int? = null,
    val trialsInconclusive: Int? = null,
    val cleanupVerified: Boolean? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val failure: String? = null,
)

data class StoredTrialResult(
    val runId: UUID,
    val trialNumber: Int,
    val outcome: TrialOutcome,
    val stateChanged: Boolean,
    val completed: Boolean,
    val failure: String?,
    val verdicts: List<InvariantVerdict>,
    val timings: List<StepTiming>,
    val observations: Map<String, ObservedEvidence> = emptyMap(),
    val faultEvents: List<FaultAuditEvent> = emptyList(),
)

data class StoredResetResult(
    val runId: UUID,
    val sequenceNumber: Int,
    val performed: Boolean,
    val verified: Boolean,
    val checks: List<ResetCheck>,
    val failure: String?,
)
