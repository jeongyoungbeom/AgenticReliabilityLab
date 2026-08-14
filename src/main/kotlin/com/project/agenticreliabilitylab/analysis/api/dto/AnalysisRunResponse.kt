package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunStatus
import java.time.Instant

data class AnalysisRunResponse(
    val id: String,
    val experimentRunId: String?,
    val targetTestBatchId: String?,
    val agentType: String,
    val agentVersion: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val analysisDatasetId: String?,
    val status: AnalysisRunStatus,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    companion object {
        fun from(run: AnalysisRunRecord) = AnalysisRunResponse(
            id = run.id.toString(),
            experimentRunId = run.experimentRunId?.toString(),
            targetTestBatchId = run.targetTestBatchId?.toString(),
            agentType = run.agentType,
            agentVersion = run.agentVersion,
            modelKey = run.modelKey,
            modelId = run.modelId,
            promptVersion = run.promptVersion,
            analysisDatasetId = run.analysisDatasetId?.toString(),
            status = run.status,
            requestedAt = run.requestedAt,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
        )
    }
}
