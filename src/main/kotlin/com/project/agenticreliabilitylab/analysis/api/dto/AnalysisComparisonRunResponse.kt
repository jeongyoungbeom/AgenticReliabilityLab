package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.application.model.ComparisonAnalysisRun
import com.project.agenticreliabilitylab.analysis.domain.AnalysisArchitecture
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunStatus
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict

data class AnalysisComparisonRunResponse(
    val selectionKey: String,
    val architecture: AnalysisArchitecture,
    val modelKey: String,
    val analysisRunId: String,
    val status: AnalysisRunStatus,
    val modelId: String,
    val verdict: AnalysisVerdict?,
    val summary: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
) {
    companion object {
        fun from(run: ComparisonAnalysisRun) = AnalysisComparisonRunResponse(
            selectionKey = run.configuration.selectionKey,
            architecture = run.configuration.architecture,
            modelKey = run.configuration.modelKey,
            analysisRunId = run.mapping.analysisRunId.toString(),
            status = run.details.run.status,
            modelId = run.details.run.modelId,
            verdict = run.details.run.verdict,
            summary = run.details.run.summary,
            promptTokenCount = run.details.run.promptTokenCount,
            completionTokenCount = run.details.run.completionTokenCount,
            durationMillis = run.details.run.durationMillis,
        )
    }
}
