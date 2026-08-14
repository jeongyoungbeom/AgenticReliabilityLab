package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.experiment.domain.ExperimentEvidenceRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
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
