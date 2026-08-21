package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.experiment.domain.ExperimentEvidenceRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import java.util.UUID

/** Read-only source contracts used while freezing an immutable analysis dataset. */
interface ExperimentAnalysisEvidenceSource {
    fun findExperimentRun(id: UUID): ExperimentRunRecord?
    fun findExperimentEvidence(runId: UUID): List<ExperimentEvidenceRecord>
}

interface TargetTestBatchAnalysisEvidenceSource {
    fun findTargetTestBatch(id: UUID): TargetTestBatchRecord?
    fun findTargetTestBatchItems(batchId: UUID): List<TargetTestBatchItemRecord>
}

/**
 * A specification run as analysis input.
 *
 * This is the source that carries *why* rather than only *what*. An experiment says a stock ended at -3; a
 * specification run says which invariant that violated, on what condition, and - when the Target is traced - which
 * requests interleaved and by how much.
 */
interface TestSpecRunAnalysisEvidenceSource {
    fun findTestSpecRun(id: UUID): TestSpecRun?
    fun findTestSpecTrials(runId: UUID): List<StoredTrialResult>
}
