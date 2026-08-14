package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisEvaluationRecord
import java.time.Instant

data class AnalysisEvaluationResponse(
    val id: String,
    val analysisRunId: String,
    val groundTruthId: String,
    val evaluationVersion: String,
    val verdictMatch: Boolean,
    val citedRequiredEvidenceCount: Int,
    val requiredEvidenceCount: Int,
    val citationRecall: Double,
    val score: Double,
    val evaluatedAt: Instant,
) {
    companion object {
        fun from(evaluation: AnalysisEvaluationRecord) = AnalysisEvaluationResponse(
            id = evaluation.id.toString(),
            analysisRunId = evaluation.analysisRunId.toString(),
            groundTruthId = evaluation.analysisGroundTruthId.toString(),
            evaluationVersion = evaluation.evaluationVersion,
            verdictMatch = evaluation.verdictMatch,
            citedRequiredEvidenceCount = evaluation.citedRequiredEvidenceCount,
            requiredEvidenceCount = evaluation.requiredEvidenceCount,
            citationRecall = evaluation.citationRecall,
            score = evaluation.score,
            evaluatedAt = evaluation.evaluatedAt,
        )
    }
}
