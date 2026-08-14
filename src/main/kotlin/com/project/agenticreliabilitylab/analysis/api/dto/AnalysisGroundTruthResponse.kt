package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisGroundTruthRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import java.time.Instant

data class AnalysisGroundTruthResponse(
    val id: String,
    val analysisDatasetId: String,
    val version: String,
    val expectedVerdict: AnalysisVerdict,
    val requiredEvidenceIds: List<String>,
    val notes: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(groundTruth: AnalysisGroundTruthRecord) = AnalysisGroundTruthResponse(
            id = groundTruth.id.toString(),
            analysisDatasetId = groundTruth.analysisDatasetId.toString(),
            version = groundTruth.version,
            expectedVerdict = groundTruth.expectedVerdict,
            requiredEvidenceIds = groundTruth.requiredEvidenceIds,
            notes = groundTruth.notes,
            createdAt = groundTruth.createdAt,
        )
    }
}
