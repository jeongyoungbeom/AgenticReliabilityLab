package com.project.agenticreliabilitylab.targetspec.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TargetTestCandidateKind {
    HEALTH_REACHABILITY,
    HTTP_STATUS_ASSERTION,
}

enum class FailureInjectionType {
    CONSUMER_RESTART,
    REDIS_FAILURE,
    SERVICE_RESTART,
    SHIPPING_SAGA_FAILURE,
}

enum class FailureInjectionRisk {
    MODERATE,
    DESTRUCTIVE,
}

enum class FailureInjectionPlanStatus {
    PENDING_APPROVAL,
    APPROVED,
}

enum class TargetTestBatchStatus {
    PENDING_APPROVAL,
    APPROVED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED,
}

enum class TargetTestBatchItemStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    BLOCKED,
}

data class TargetTestCandidate(
    val id: String,
    val targetSystemId: String,
    val kind: TargetTestCandidateKind,
    val title: String,
    val description: String,
    val method: String,
    val path: String,
    val expectedStatusCodes: Set<Int>,
    val timeout: Duration,
)

/** Declarative plan metadata only; it intentionally contains no command or endpoint to execute. */
data class FailureInjectionCandidate(
    val id: String,
    val targetSystemId: String,
    val type: FailureInjectionType,
    val risk: FailureInjectionRisk,
    val title: String,
    val description: String,
    val recoveryExpectation: String,
)

data class FailureInjectionPlanRecord(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID?,
    val idempotencyKey: String,
    val requestHash: String,
    val status: FailureInjectionPlanStatus,
    val approvedAt: Instant?,
    val approvedBy: String? = null,
    val approvalCorrelationId: String? = null,
    val createdAt: Instant,
)

data class FailureInjectionPlanItemRecord(
    val id: UUID,
    val planId: UUID,
    val sequenceNumber: Int,
    val candidateId: String,
    val type: FailureInjectionType,
    val risk: FailureInjectionRisk,
    val title: String,
    val recoveryExpectation: String,
)

data class TargetTestBatchRecord(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID?,
    val idempotencyKey: String,
    val requestHash: String,
    val status: TargetTestBatchStatus,
    val approvedAt: Instant?,
    val approvedBy: String? = null,
    val approvalCorrelationId: String? = null,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val failureMessage: String?,
)

data class TargetTestBatchItemRecord(
    val id: UUID,
    val batchId: UUID,
    val candidateId: String,
    val sequenceNumber: Int,
    val kind: TargetTestCandidateKind,
    val title: String,
    val method: String,
    val path: String,
    val expectedStatusCodes: Set<Int>,
    val timeout: Duration,
    val status: TargetTestBatchItemStatus,
    val httpStatus: Int?,
    val latencyMs: Long?,
    val resultJson: String?,
    val failureMessage: String?,
    val startedAt: Instant?,
    val completedAt: Instant?,
)
