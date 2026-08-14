package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunDetails
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunStatus
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import com.project.agenticreliabilitylab.analysis.domain.FindingSeverity
import com.project.agenticreliabilitylab.analysis.domain.RecommendationPriority
import java.time.Instant
import java.util.UUID

/** Persistence boundary for the single-agent analysis lifecycle. */
interface AnalysisRunStore {
    fun findById(id: UUID): AnalysisRunRecord?
    fun findByExperimentAndIdempotencyKey(experimentRunId: UUID, idempotencyKey: String): AnalysisRunRecord?
    fun findByTargetTestBatchAndIdempotencyKey(targetTestBatchId: UUID, idempotencyKey: String): AnalysisRunRecord?
    fun findIdsByAgentTypeAndStatus(agentType: String, status: AnalysisRunStatus): List<UUID>
    fun findDetails(id: UUID): AnalysisRunDetails?
    fun create(run: NewAnalysisRun)
    fun claimForExecution(id: UUID, now: Instant): Boolean
    fun complete(id: UUID, completion: AnalysisCompletion, now: Instant)
    fun fail(id: UUID, failureCode: String, failureMessage: String, now: Instant)
}

data class NewAnalysisRun(
    val id: UUID,
    val experimentRunId: UUID? = null,
    val targetTestBatchId: UUID? = null,
    val idempotencyKey: String,
    val agentType: String,
    val agentVersion: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val analysisDatasetId: UUID,
    val inputChecksum: String,
    val inputEvidenceCount: Int,
    val requestedAt: Instant,
) {
    init {
        require((experimentRunId == null) != (targetTestBatchId == null)) {
            "An analysis run must belong to exactly one source"
        }
    }
}

data class AnalysisCompletion(
    val summary: String,
    val verdict: AnalysisVerdict,
    val outputJson: String,
    val findings: List<NewAnalysisFinding>,
    val recommendations: List<NewAnalysisRecommendation>,
    val promptTokenCount: Int? = null,
    val completionTokenCount: Int? = null,
    val durationMillis: Long? = null,
)

data class NewAnalysisFinding(
    val severity: FindingSeverity,
    val title: String,
    val rationale: String,
    val evidenceIds: List<String>,
)

data class NewAnalysisRecommendation(
    val priority: RecommendationPriority,
    val title: String,
    val recommendedAction: String,
    val rationale: String,
    val evidenceIds: List<String>,
)
