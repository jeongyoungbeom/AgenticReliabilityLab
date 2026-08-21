package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisGroundTruthRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import java.time.Instant
import java.util.UUID

/** Persistence boundary for immutable analysis inputs and their ground truth. */
interface AnalysisDatasetStore {
    fun create(dataset: NewAnalysisDataset)
    fun findById(id: UUID): AnalysisDatasetRecord?
    fun createGroundTruth(groundTruth: NewAnalysisGroundTruth)
    fun findGroundTruth(id: UUID): AnalysisGroundTruthRecord?
}

data class NewAnalysisDataset(
    val id: UUID,
    val experimentRunId: UUID? = null,
    val targetTestBatchId: UUID? = null,
    val testSpecRunId: UUID? = null,
    val contractVersion: String,
    val evidenceBundleJson: String,
    val evidenceIds: List<String>,
    val checksum: String,
    val createdAt: Instant,
) {
    init {
        require(listOfNotNull(experimentRunId, targetTestBatchId, testSpecRunId).size == 1) {
            "An analysis dataset must belong to exactly one source"
        }
    }
}

data class NewAnalysisGroundTruth(
    val id: UUID,
    val analysisDatasetId: UUID,
    val version: String,
    val expectedVerdict: AnalysisVerdict,
    val requiredEvidenceIds: List<String>,
    val notes: String?,
    val createdAt: Instant,
)
