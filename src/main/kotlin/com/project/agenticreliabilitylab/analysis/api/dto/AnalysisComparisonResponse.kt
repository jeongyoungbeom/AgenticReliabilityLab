package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.application.model.AnalysisComparisonDetails
import java.time.Instant

data class AnalysisComparisonResponse(
    val id: String,
    val experimentRunId: String?,
    val targetTestBatchId: String?,
    val analysisDatasetId: String,
    val datasetChecksum: String,
    val evidenceCount: Int,
    val modelKeys: List<String>,
    val selectedConfigurations: List<AnalysisComparisonConfigurationResponse>,
    val requestedAt: Instant,
    val runs: List<AnalysisComparisonRunResponse>,
) {
    companion object {
        fun from(details: AnalysisComparisonDetails) = AnalysisComparisonResponse(
            id = details.comparison.id.toString(),
            experimentRunId = details.comparison.experimentRunId?.toString(),
            targetTestBatchId = details.comparison.targetTestBatchId?.toString(),
            analysisDatasetId = details.dataset.id.toString(),
            datasetChecksum = details.dataset.checksum,
            evidenceCount = details.dataset.evidenceCount,
            modelKeys = details.comparison.modelKeys,
            selectedConfigurations = details.configurations.map(AnalysisComparisonConfigurationResponse::from),
            requestedAt = details.comparison.requestedAt,
            runs = details.runs.map(AnalysisComparisonRunResponse::from),
        )
    }
}
