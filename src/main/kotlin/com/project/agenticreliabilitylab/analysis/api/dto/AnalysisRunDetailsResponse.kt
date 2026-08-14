package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunDetails
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunStatus
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import java.time.Instant

data class AnalysisRunDetailsResponse(
    val id: String,
    val experimentRunId: String?,
    val targetTestBatchId: String?,
    val agentType: String,
    val agentVersion: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val analysisDatasetId: String?,
    val inputChecksum: String?,
    val inputEvidenceCount: Int?,
    val status: AnalysisRunStatus,
    val verdict: AnalysisVerdict?,
    val summary: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val findings: List<AnalysisFindingResponse>,
    val recommendations: List<AnalysisRecommendationResponse>,
) {
    companion object {
        fun from(details: AnalysisRunDetails) = AnalysisRunDetailsResponse(
            id = details.run.id.toString(),
            experimentRunId = details.run.experimentRunId?.toString(),
            targetTestBatchId = details.run.targetTestBatchId?.toString(),
            agentType = details.run.agentType,
            agentVersion = details.run.agentVersion,
            modelKey = details.run.modelKey,
            modelId = details.run.modelId,
            promptVersion = details.run.promptVersion,
            analysisDatasetId = details.run.analysisDatasetId?.toString(),
            inputChecksum = details.run.inputChecksum,
            inputEvidenceCount = details.run.inputEvidenceCount,
            status = details.run.status,
            verdict = details.run.verdict,
            summary = details.run.summary,
            failureCode = details.run.failureCode,
            failureMessage = details.run.failureMessage,
            promptTokenCount = details.run.promptTokenCount,
            completionTokenCount = details.run.completionTokenCount,
            durationMillis = details.run.durationMillis,
            requestedAt = details.run.requestedAt,
            startedAt = details.run.startedAt,
            completedAt = details.run.completedAt,
            findings = details.findings.map(AnalysisFindingResponse::from),
            recommendations = details.recommendations.map(AnalysisRecommendationResponse::from),
        )
    }
}
