package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.analysis.application.port.TestSpecRunAnalysisEvidenceSource
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Exposes a finished specification run to the analysis path, and nothing more.
 *
 * The run store could implement the analysis port directly, but it would then carry a second name for reads it
 * already has, and every later reader would have to work out whether the two vocabularies meant the same thing.
 * A translation with one job says plainly what the analysis path is allowed to see: a completed run and its
 * trials. Not the execution slot, not the recovery bookkeeping, not the idempotency lookups.
 */
@Component
class TestSpecRunEvidenceSource(
    private val runs: TestSpecRunStore,
) : TestSpecRunAnalysisEvidenceSource {
    override fun findTestSpecRun(id: UUID): TestSpecRun? = runs.findById(id)

    override fun findTestSpecTrials(runId: UUID): List<StoredTrialResult> = runs.findTrials(runId)
}
