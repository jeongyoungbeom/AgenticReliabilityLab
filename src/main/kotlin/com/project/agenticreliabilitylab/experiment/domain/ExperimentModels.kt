package com.project.agenticreliabilitylab.experiment.domain

import java.time.Instant
import java.util.UUID

enum class ExperimentType {
    STOCK_CONCURRENCY,
}

enum class ExperimentRunStatus {
    CREATED,
    VALIDATING,
    PREPARING,
    RUNNING,
    COLLECTING,
    CLEANING,
    COMPLETED,
    VALIDATION_FAILED,
    FAILED,
    TIMED_OUT,
    ABORTED,
    CANCELED,
    RECOVERY_REQUIRED,
}

enum class SystemOutcome {
    NOT_EVALUATED,
    PASSED,
    FAILED,
    INCONCLUSIVE,
    UNKNOWN,
}

enum class CleanupStatus {
    NOT_REQUIRED,
    PENDING,
    VERIFIED,
    FAILED,
    UNKNOWN,
}

enum class ExperimentActionStatus {
    PLANNED,
    DISPATCHED,
    CONFIRMED,
    UNKNOWN,
}

data class StockConcurrencyParameters(
    val stock: Int,
    val requestCount: Int,
    val concurrency: Int,
    val quantityPerRequest: Int,
)

data class ExperimentRunRecord(
    val id: UUID,
    val targetSystemId: String,
    val experimentType: ExperimentType,
    val experimentDefinitionVersion: String,
    val parametersJson: String,
    val plannedRunSpecId: UUID,
    val idempotencyKey: String,
    val runStatus: ExperimentRunStatus,
    val systemOutcome: SystemOutcome,
    val invariantResultJson: String?,
    val outcomeReason: String?,
    val cleanupStatus: CleanupStatus,
    val cleanupFailureCode: String?,
    val cleanupFailureMessage: String?,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class ExperimentEvidenceRecord(
    val id: UUID,
    val experimentRunId: UUID,
    val evidenceType: String,
    val schemaVersion: String,
    val source: String,
    val observedAt: Instant?,
    val completeness: String,
    val payloadJson: String,
    val artifactRefsJson: String,
    val checksum: String,
    val createdAt: Instant,
)

data class WorkloadLease(
    val hostResourceGroup: String,
    val ownerId: String,
    val leaseOwner: String,
    val fencingToken: Long,
    val leaseExpiresAt: Instant,
)

/** Whether one named invariant held, broke, or could not be judged from what the Target actually reported. */
enum class InvariantOutcome {
    PASSED,
    FAILED,
    NOT_EVALUATED,
}

/**
 * One named invariant and the evidence behind its verdict.
 *
 * A bare pass/fail flag cannot answer "what broke, and how do you know". Carrying the expected and observed values with
 * each verdict is what lets a result say which rule was violated and by how much, and lets a later analysis cite the
 * specific invariant rather than restating that the experiment failed.
 *
 * [InvariantOutcome.NOT_EVALUATED] is deliberately distinct from a failure: an observation the Target never reported
 * is missing evidence, not a violated rule, and reporting it as a violation would invent a finding.
 */
data class InvariantVerdict(
    val id: String,
    val title: String,
    val outcome: InvariantOutcome,
    val expected: String,
    val observed: String,
    val detail: String,
)

data class InvariantEvaluation(
    val outcome: SystemOutcome,
    val reason: String,
    val payloadJson: String,
)
