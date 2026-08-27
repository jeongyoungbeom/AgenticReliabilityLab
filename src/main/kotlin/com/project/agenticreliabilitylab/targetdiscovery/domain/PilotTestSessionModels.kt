package com.project.agenticreliabilitylab.targetdiscovery.domain

import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import java.time.Instant
import java.util.UUID

/** One human-approved Pilot selection, kept separately from the child Test Specification runs it created. */
enum class PilotTestSessionStatus {
    RUNNING,
    COMPLETED,
    RECOVERY_REQUIRED,
}

enum class PilotTestSessionItemStatus {
    COMPLETED,
    FAILED,
    RECOVERY_REQUIRED,
}

data class PilotTestSession(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val status: PilotTestSessionStatus,
    val idempotencyKey: String,
    val requestHash: String,
    val createdBy: String,
    val createdCorrelationId: String,
    val createdAt: Instant,
    val resultOutcome: TrialOutcome? = null,
    val cleanupVerified: Boolean? = null,
    val completedAt: Instant? = null,
    val failure: String? = null,
)

/** A session item stores only references and verdict metadata; detailed evidence remains owned by Test Spec Run. */
data class PilotTestSessionItem(
    val sessionId: UUID,
    val sequenceNumber: Int,
    val candidateId: String,
    val specificationId: UUID?,
    val testSpecRunId: UUID?,
    val status: PilotTestSessionItemStatus,
    val resultOutcome: TrialOutcome?,
    val cleanupVerified: Boolean?,
    val failureCode: String?,
    val failureMessage: String?,
    val completedAt: Instant,
)
