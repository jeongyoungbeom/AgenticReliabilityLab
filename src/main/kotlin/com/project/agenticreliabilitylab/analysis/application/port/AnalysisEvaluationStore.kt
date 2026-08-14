package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.analysis.domain.AnalysisComparisonRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisComparisonRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisEvaluationRecord
import java.time.Instant
import java.util.UUID

/** Persistence boundary for evaluation and selected-configuration comparisons. */
interface AnalysisEvaluationStore {
    fun createComparison(comparison: NewAnalysisComparison)
    fun findComparison(id: UUID): AnalysisComparisonRecord?
    fun findComparisonByExperimentAndIdempotencyKey(
        experimentRunId: UUID,
        idempotencyKey: String,
    ): AnalysisComparisonRecord?
    fun findComparisonByTargetTestBatchAndIdempotencyKey(
        targetTestBatchId: UUID,
        idempotencyKey: String,
    ): AnalysisComparisonRecord?
    fun attachComparisonRun(comparisonId: UUID, modelKey: String, analysisRunId: UUID)
    fun findComparisonRuns(comparisonId: UUID): List<AnalysisComparisonRunRecord>
    fun createEvaluation(evaluation: NewAnalysisEvaluation)
    fun findEvaluation(
        analysisRunId: UUID,
        groundTruthId: UUID,
        evaluationVersion: String,
    ): AnalysisEvaluationRecord?
}

data class NewAnalysisComparison(
    val id: UUID,
    val experimentRunId: UUID? = null,
    val targetTestBatchId: UUID? = null,
    val analysisDatasetId: UUID,
    val idempotencyKey: String,
    val modelKeys: List<String>,
    val configurationJson: String? = null,
    val configurationHash: String? = null,
    val requestedAt: Instant,
) {
    init {
        require((experimentRunId == null) != (targetTestBatchId == null)) {
            "An analysis comparison must belong to exactly one source"
        }
        require((configurationJson == null) == (configurationHash == null)) {
            "A comparison configuration must provide both JSON and hash, or neither"
        }
    }
}

data class NewAnalysisEvaluation(
    val id: UUID,
    val analysisRunId: UUID,
    val analysisGroundTruthId: UUID,
    val evaluationVersion: String,
    val verdictMatch: Boolean,
    val citedRequiredEvidenceCount: Int,
    val requiredEvidenceCount: Int,
    val citationRecall: Double,
    val score: Double,
    val evaluatedAt: Instant,
)
