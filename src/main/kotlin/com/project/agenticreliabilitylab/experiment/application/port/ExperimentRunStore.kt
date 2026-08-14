package com.project.agenticreliabilitylab.experiment.application.port

import com.project.agenticreliabilitylab.analysis.application.port.ExperimentAnalysisEvidenceSource
import com.project.agenticreliabilitylab.experiment.domain.CleanupStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentEvidenceRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunStatus
import com.project.agenticreliabilitylab.experiment.domain.SystemOutcome
import java.time.Instant
import java.util.UUID

/** Persistence boundary for the durable experiment state machine. */
@Suppress("TooManyFunctions") // One experiment aggregate owns lifecycle, evidence, actions, and recovery.
interface ExperimentRunStore : ExperimentAnalysisEvidenceSource {
    fun findById(id: UUID): ExperimentRunRecord?
    fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): ExperimentRunRecord?
    fun create(run: NewExperimentRun)
    fun claimForExecution(runId: UUID, now: Instant): Boolean
    fun updateRunStatus(runId: UUID, status: ExperimentRunStatus, now: Instant)
    fun attachManifest(runId: UUID, phase: ManifestPhase, payloadJson: String, checksum: String, now: Instant): UUID
    fun insertAction(runId: UUID, actionId: String, requestHash: String, fencingToken: Long, now: Instant)
    fun markActionDispatched(runId: UUID, actionId: String, now: Instant)
    fun markActionConfirmed(runId: UUID, actionId: String, targetOperationId: String, now: Instant)
    fun markActionsUnknown(runId: UUID, message: String)
    fun insertResource(
        runId: UUID,
        actionId: String,
        resourceType: String,
        resourceId: String,
        namespace: String,
        cleanupStatus: CleanupStatus,
    )
    fun insertEvidence(evidence: NewEvidence)
    fun insertArtifact(artifact: NewArtifact)
    fun complete(runId: UUID, completion: RunCompletion)
    fun findEvidence(runId: UUID): List<ExperimentEvidenceRecord>
    fun hasBlockingCleanup(targetSystemId: String): Boolean
    fun findInProgressRunIds(): List<UUID>
    fun findCreatedRunIds(): List<UUID>
    fun markSchedulingFailed(runId: UUID, now: Instant, message: String)
    fun markRecoveryRequired(runId: UUID, now: Instant, message: String)
}

data class NewExperimentRun(
    val id: UUID,
    val targetSystemId: String,
    val campaignRunId: UUID? = null,
    val campaignStepRunId: UUID? = null,
    val definitionVersion: String,
    val parametersJson: String,
    val plannedRunSpecId: UUID,
    val idempotencyKey: String,
    val loadProfileJson: String,
    val fixturePlanJson: String,
    val hostResourceGroup: String,
    val specHash: String,
    val queuedAt: Instant,
)

enum class ManifestPhase { PRE_RUN, POST_RUN }

data class NewEvidence(
    val id: UUID,
    val runId: UUID,
    val evidenceType: String,
    val schemaVersion: String,
    val source: String,
    val collectorVersion: String,
    val observedAt: Instant,
    val completeness: String,
    val payloadJson: String,
    val artifactRefsJson: String,
    val checksum: String,
    val createdAt: Instant,
)

data class NewArtifact(
    val id: UUID,
    val runId: UUID,
    val artifactType: String,
    val storageReference: String,
    val checksum: String,
    val createdAt: Instant,
)

data class RunCompletion(
    val runStatus: ExperimentRunStatus,
    val systemOutcome: SystemOutcome,
    val invariantResultJson: String?,
    val outcomeReason: String?,
    val definitionVersion: String?,
    val failurePhase: String?,
    val failureOwner: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val cleanupStatus: CleanupStatus,
    val cleanupFailureCode: String?,
    val cleanupFailureMessage: String?,
    val completedAt: Instant,
)
