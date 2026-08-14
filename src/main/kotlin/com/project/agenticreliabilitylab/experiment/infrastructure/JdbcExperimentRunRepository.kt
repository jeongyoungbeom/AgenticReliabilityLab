package com.project.agenticreliabilitylab.experiment.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.experiment.application.port.ExperimentRunStore
import com.project.agenticreliabilitylab.experiment.application.port.ManifestPhase
import com.project.agenticreliabilitylab.experiment.application.port.NewArtifact
import com.project.agenticreliabilitylab.experiment.application.port.NewEvidence
import com.project.agenticreliabilitylab.experiment.application.port.NewExperimentRun
import com.project.agenticreliabilitylab.experiment.application.port.RunCompletion
import com.project.agenticreliabilitylab.experiment.domain.CleanupStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentActionStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentEvidenceRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.experiment.domain.SystemOutcome
import com.project.agenticreliabilitylab.experiment.infrastructure.sql.ExperimentRunSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Suppress("TooManyFunctions") // Persistence methods mirror the normalized experiment tables.
@Repository
class JdbcExperimentRunRepository(
    private val jdbcClient: JdbcClient,
    private val identifierGenerator: IdentifierGenerator,
) : ExperimentRunStore {
    override fun findExperimentRun(id: UUID): ExperimentRunRecord? = findById(id)

    override fun findById(id: UUID): ExperimentRunRecord? =
        jdbcClient.sql(ExperimentRunSql.FIND_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toRun() }
            .optional()
            .orElse(null)

    override fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): ExperimentRunRecord? =
        jdbcClient.sql(ExperimentRunSql.FIND_BY_TARGET_AND_IDEMPOTENCY)
            .param("targetSystemId", targetSystemId)
            .param("idempotencyKey", idempotencyKey)
            .query { resultSet, _ -> resultSet.toRun() }
            .optional()
            .orElse(null)

    @Transactional
    @Suppress("LongMethod") // Creation atomically records the immutable specification and initial run.
    override fun create(run: NewExperimentRun) {
        jdbcClient.sql(ExperimentRunSql.INSERT_PLANNED_RUN_SPEC).params(
            mapOf(
                "id" to run.plannedRunSpecId,
                "targetSystemId" to run.targetSystemId,
                "definitionVersion" to run.definitionVersion,
                "parametersJson" to run.parametersJson,
                "loadProfileJson" to run.loadProfileJson,
                "fixturePlanJson" to run.fixturePlanJson,
                "hostResourceGroup" to run.hostResourceGroup,
                "specHash" to run.specHash,
                "createdAt" to Timestamp.from(run.queuedAt),
            ),
        ).update()

        jdbcClient.sql(ExperimentRunSql.INSERT_RUN).params(
            mapOf(
                "id" to run.id,
                "targetSystemId" to run.targetSystemId,
                "campaignRunId" to run.campaignRunId,
                "campaignStepRunId" to run.campaignStepRunId,
                "experimentType" to ExperimentType.STOCK_CONCURRENCY.name,
                "definitionVersion" to run.definitionVersion,
                "parametersJson" to run.parametersJson,
                "plannedRunSpecId" to run.plannedRunSpecId,
                "idempotencyKey" to run.idempotencyKey,
                "runStatus" to ExperimentRunStatus.CREATED.name,
                "systemOutcome" to SystemOutcome.NOT_EVALUATED.name,
                "cleanupStatus" to CleanupStatus.NOT_REQUIRED.name,
                "queuedAt" to Timestamp.from(run.queuedAt),
            ),
        ).update()

    }

    override fun claimForExecution(runId: UUID, now: Instant): Boolean =
        jdbcClient.sql(ExperimentRunSql.CLAIM_FOR_EXECUTION).params(
            mapOf(
                "id" to runId,
                "created" to ExperimentRunStatus.CREATED.name,
                "validating" to ExperimentRunStatus.VALIDATING.name,
                "startedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    override fun updateRunStatus(runId: UUID, status: ExperimentRunStatus, now: Instant) {
        jdbcClient.sql(ExperimentRunSql.UPDATE_RUN_STATUS)
            .params(mapOf("id" to runId, "status" to status.name, "now" to Timestamp.from(now)))
            .update()
    }

    override fun attachManifest(
        runId: UUID,
        phase: ManifestPhase,
        payloadJson: String,
        checksum: String,
        now: Instant,
    ): UUID {
        val manifestId = identifierGenerator.next()
        jdbcClient.sql(ExperimentRunSql.INSERT_MANIFEST).params(
            mapOf(
                "manifestId" to manifestId,
                "phase" to phase.name,
                "payloadJson" to payloadJson,
                "checksum" to checksum,
                "observedAt" to Timestamp.from(now),
                "runId" to runId,
            ),
        ).update()

        val manifestReferenceUpdate = when (phase) {
            ManifestPhase.PRE_RUN -> ExperimentRunSql.UPDATE_PRE_RUN_MANIFEST
            ManifestPhase.POST_RUN -> ExperimentRunSql.UPDATE_POST_RUN_MANIFEST
        }
        jdbcClient.sql(manifestReferenceUpdate)
            .param("manifestId", manifestId)
            .param("runId", runId)
            .update()
        return manifestId
    }

    override fun insertAction(
        runId: UUID,
        actionId: String,
        requestHash: String,
        fencingToken: Long,
        now: Instant,
    ) {
        jdbcClient.sql(ExperimentRunSql.INSERT_ACTION).params(
            mapOf(
                "id" to identifierGenerator.next(),
                "runId" to runId,
                "actionId" to actionId,
                "actionType" to "STOCK_CONCURRENCY_SCRIPT",
                "requestHash" to requestHash,
                "status" to ExperimentActionStatus.PLANNED.name,
                "fencingToken" to fencingToken,
                "now" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun markActionDispatched(runId: UUID, actionId: String, now: Instant) {
        jdbcClient.sql(ExperimentRunSql.MARK_ACTION_DISPATCHED).params(
            mapOf(
                "runId" to runId,
                "actionId" to actionId,
                "status" to ExperimentActionStatus.DISPATCHED.name,
                "planned" to ExperimentActionStatus.PLANNED.name,
                "dispatchedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun markActionConfirmed(runId: UUID, actionId: String, targetOperationId: String, now: Instant) {
        jdbcClient.sql(ExperimentRunSql.MARK_ACTION_CONFIRMED).params(
            mapOf(
                "runId" to runId,
                "actionId" to actionId,
                "status" to ExperimentActionStatus.CONFIRMED.name,
                "targetOperationId" to targetOperationId,
                "confirmedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun markActionsUnknown(runId: UUID, message: String) {
        jdbcClient.sql(ExperimentRunSql.MARK_ACTIONS_UNKNOWN).params(
            mapOf(
                "runId" to runId,
                "unknown" to ExperimentActionStatus.UNKNOWN.name,
                "dispatched" to ExperimentActionStatus.DISPATCHED.name,
                "message" to message.take(1000),
            ),
        ).update()
    }

    override fun insertResource(
        runId: UUID,
        actionId: String,
        resourceType: String,
        resourceId: String,
        namespace: String,
        cleanupStatus: CleanupStatus,
    ) {
        jdbcClient.sql(ExperimentRunSql.INSERT_RESOURCE).params(
            mapOf(
                "id" to identifierGenerator.next(),
                "runId" to runId,
                "actionId" to actionId,
                "resourceType" to resourceType,
                "resourceId" to resourceId,
                "namespace" to namespace,
                "cleanupStatus" to cleanupStatus.name,
            ),
        ).update()
    }

    override fun insertEvidence(evidence: NewEvidence) {
        jdbcClient.sql(ExperimentRunSql.INSERT_EVIDENCE).params(
            mapOf(
                "id" to evidence.id,
                "runId" to evidence.runId,
                "evidenceType" to evidence.evidenceType,
                "schemaVersion" to evidence.schemaVersion,
                "source" to evidence.source,
                "collectorVersion" to evidence.collectorVersion,
                "observedAt" to Timestamp.from(evidence.observedAt),
                "completeness" to evidence.completeness,
                "payloadJson" to evidence.payloadJson,
                "artifactRefsJson" to evidence.artifactRefsJson,
                "checksum" to evidence.checksum,
                "createdAt" to Timestamp.from(evidence.createdAt),
            ),
        ).update()
    }

    override fun insertArtifact(artifact: NewArtifact) {
        jdbcClient.sql(ExperimentRunSql.INSERT_ARTIFACT).params(
            mapOf(
                "id" to artifact.id,
                "runId" to artifact.runId,
                "artifactType" to artifact.artifactType,
                "storageReference" to artifact.storageReference,
                "checksum" to artifact.checksum,
                "createdAt" to Timestamp.from(artifact.createdAt),
            ),
        ).update()
    }

    override fun complete(runId: UUID, completion: RunCompletion) {
        jdbcClient.sql(ExperimentRunSql.COMPLETE_RUN).params(
            mapOf(
                "id" to runId,
                "runStatus" to completion.runStatus.name,
                "systemOutcome" to completion.systemOutcome.name,
                "invariantResultJson" to completion.invariantResultJson,
                "outcomeReason" to completion.outcomeReason?.take(1000),
                "definitionVersion" to completion.definitionVersion,
                "failurePhase" to completion.failurePhase,
                "failureOwner" to completion.failureOwner,
                "failureCode" to completion.failureCode,
                "failureMessage" to completion.failureMessage?.take(1000),
                "cleanupStatus" to completion.cleanupStatus.name,
                "cleanupFailureCode" to completion.cleanupFailureCode,
                "cleanupFailureMessage" to completion.cleanupFailureMessage?.take(1000),
                "completedAt" to Timestamp.from(completion.completedAt),
            ),
        ).update()
    }

    override fun findExperimentEvidence(runId: UUID): List<ExperimentEvidenceRecord> = findEvidence(runId)

    override fun findEvidence(runId: UUID): List<ExperimentEvidenceRecord> =
        jdbcClient.sql(ExperimentRunSql.FIND_EVIDENCE).param("runId", runId)
            .query { resultSet, _ ->
                ExperimentEvidenceRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    experimentRunId = resultSet.getObject("experiment_run_id", UUID::class.java),
                    evidenceType = resultSet.getString("evidence_type"),
                    schemaVersion = resultSet.getString("schema_version"),
                    source = resultSet.getString("source"),
                    observedAt = resultSet.getTimestamp("observed_at")?.toInstant(),
                    completeness = resultSet.getString("completeness"),
                    payloadJson = resultSet.getString("payload_json"),
                    artifactRefsJson = resultSet.getString("artifact_refs_json"),
                    checksum = resultSet.getString("checksum"),
                    createdAt = resultSet.getTimestamp("created_at").toInstant(),
                )
            }.list()

    override fun hasBlockingCleanup(targetSystemId: String): Boolean =
        jdbcClient.sql(ExperimentRunSql.HAS_BLOCKING_CLEANUP).params(
            mapOf(
                "targetSystemId" to targetSystemId,
                "failed" to CleanupStatus.FAILED.name,
                "unknown" to CleanupStatus.UNKNOWN.name,
            ),
        ).query(Long::class.java)
            .single() > 0

    override fun findInProgressRunIds(): List<UUID> =
        jdbcClient.sql(ExperimentRunSql.FIND_IN_PROGRESS_IDS).params(
            mapOf(
                "validating" to ExperimentRunStatus.VALIDATING.name,
                "preparing" to ExperimentRunStatus.PREPARING.name,
                "running" to ExperimentRunStatus.RUNNING.name,
                "collecting" to ExperimentRunStatus.COLLECTING.name,
                "cleaning" to ExperimentRunStatus.CLEANING.name,
            ),
        ).query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }.list()

    override fun findCreatedRunIds(): List<UUID> =
        jdbcClient.sql(ExperimentRunSql.FIND_CREATED_IDS)
            .param("created", ExperimentRunStatus.CREATED.name)
            .query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }.list()

    override fun markSchedulingFailed(runId: UUID, now: Instant, message: String) {
        jdbcClient.sql(ExperimentRunSql.MARK_SCHEDULING_FAILED).params(
            mapOf(
                "id" to runId,
                "created" to ExperimentRunStatus.CREATED.name,
                "failed" to ExperimentRunStatus.FAILED.name,
                "systemOutcome" to SystemOutcome.NOT_EVALUATED.name,
                "outcomeReason" to message.take(1000),
                "failurePhase" to "SCHEDULING",
                "failureOwner" to "EXPERIMENT_ENGINE",
                "failureCode" to "TASK_REJECTED",
                "failureMessage" to message.take(1000),
                "cleanupStatus" to CleanupStatus.NOT_REQUIRED.name,
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun markRecoveryRequired(runId: UUID, now: Instant, message: String) {
        markActionsUnknown(runId, message)
        complete(
            runId,
            RunCompletion(
                runStatus = ExperimentRunStatus.RECOVERY_REQUIRED,
                systemOutcome = SystemOutcome.UNKNOWN,
                invariantResultJson = null,
                outcomeReason = message,
                definitionVersion = null,
                failurePhase = "EXTERNAL_OPERATION",
                failureOwner = "EXPERIMENT_ENGINE",
                failureCode = "OUTCOME_UNKNOWN",
                failureMessage = message,
                cleanupStatus = CleanupStatus.UNKNOWN,
                cleanupFailureCode = "OUTCOME_UNKNOWN",
                cleanupFailureMessage = message,
                completedAt = now,
            ),
        )
    }

    private fun ResultSet.toRun(): ExperimentRunRecord = ExperimentRunRecord(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        experimentType = ExperimentType.valueOf(getString("experiment_type")),
        experimentDefinitionVersion = getString("experiment_definition_version"),
        parametersJson = getString("parameters_json"),
        plannedRunSpecId = getObject("planned_run_spec_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        runStatus = ExperimentRunStatus.valueOf(getString("run_status")),
        systemOutcome = SystemOutcome.valueOf(getString("system_outcome")),
        invariantResultJson = getString("invariant_result_json"),
        outcomeReason = getString("outcome_reason"),
        cleanupStatus = CleanupStatus.valueOf(getString("cleanup_status")),
        cleanupFailureCode = getString("cleanup_failure_code"),
        cleanupFailureMessage = getString("cleanup_failure_message"),
        queuedAt = getTimestamp("queued_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

}
