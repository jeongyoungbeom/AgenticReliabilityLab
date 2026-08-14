package com.project.agenticreliabilitylab.experiment.api.dto

import com.project.agenticreliabilitylab.experiment.domain.CleanupStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.experiment.domain.SystemOutcome
import java.time.Instant

data class ExperimentRunResponse(
    val id: String,
    val targetSystem: String,
    val type: ExperimentType,
    val definitionVersion: String,
    val runStatus: ExperimentRunStatus,
    val systemOutcome: SystemOutcome,
    val invariantResult: String?,
    val outcomeReason: String?,
    val cleanupStatus: CleanupStatus,
    val cleanupFailureCode: String?,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    companion object {
        fun from(run: ExperimentRunRecord) = ExperimentRunResponse(
            id = run.id.toString(), targetSystem = run.targetSystemId, type = run.experimentType,
            definitionVersion = run.experimentDefinitionVersion, runStatus = run.runStatus,
            systemOutcome = run.systemOutcome, invariantResult = run.invariantResultJson,
            outcomeReason = run.outcomeReason, cleanupStatus = run.cleanupStatus,
            cleanupFailureCode = run.cleanupFailureCode, queuedAt = run.queuedAt,
            startedAt = run.startedAt, completedAt = run.completedAt,
        )
    }
}
