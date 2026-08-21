package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.analysis.application.port.AnalysisDatasetStore
import com.project.agenticreliabilitylab.analysis.application.port.ExperimentAnalysisEvidenceSource
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisDataset
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisGroundTruth
import com.project.agenticreliabilitylab.analysis.application.port.ReliabilityAgentSettings
import com.project.agenticreliabilitylab.analysis.application.port.TargetTestBatchAnalysisEvidenceSource
import com.project.agenticreliabilitylab.analysis.application.port.TestSpecRunAnalysisEvidenceSource
import com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisGroundTruthRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentEvidenceRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import com.project.agenticreliabilitylab.testspec.domain.InvariantOutcome
import com.project.agenticreliabilitylab.testspec.domain.InvariantVerdict
import com.project.agenticreliabilitylab.testspec.domain.ObservedEvidence
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A specification run as analysis input.
 *
 * The point of this source is that it carries *why*. An experiment says a stock ended at -3; this says which
 * invariant that violated and - when the Target is traced - which requests interleaved. These tests are mostly
 * about that evidence surviving the trip: a bundle that reaches the model with the sentence but not the spans
 * would leave the whole Phase producing suggestions that cannot cite anything.
 */
class AnalysisDatasetSpecRunTests {
    private val datasets = RecordingDatasetStore()

    @Test
    fun `carries the spans a verdict was judged on into the evidence bundle`() {
        val service = service(trials = listOf(trial()))

        val dataset = service.createForTestSpecRun(RUN_ID)

        assertEquals(RUN_ID, datasets.created.single().testSpecRunId)
        val bundle = datasets.created.single().evidenceBundleJson
        // Not the verdict's rendered summary - the spans themselves, with the trace ids that pair them.
        assertTrue(bundle.contains("trace-7"), bundle)
        assertTrue(bundle.contains("1340"), bundle)
        assertEquals(1, dataset.evidenceCount)
    }

    @Test
    fun `names one evidence entry per trial so a suggestion can cite a specific run`() {
        val service = service(trials = listOf(trial(1), trial(2)))

        service.createForTestSpecRun(RUN_ID)

        assertEquals(
            listOf("test-spec-run:$RUN_ID:trial:1", "test-spec-run:$RUN_ID:trial:2"),
            datasets.created.single().evidenceIds,
        )
    }

    /**
     * An unverified cleanup means the Target was left in a state nobody confirmed, so the next run's observations
     * may describe leftovers from this one. Reasoning about causes from that is worse than not reasoning.
     */
    @Test
    fun `refuses a run whose cleanup was not verified`() {
        val service = service(trials = listOf(trial()), cleanupVerified = false)

        val failure = assertFailsWith<AnalysisRequestException> { service.createForTestSpecRun(RUN_ID) }

        assertTrue(failure.message.orEmpty().contains("cleanup"))
    }

    @Test
    fun `refuses a run that has not finished`() {
        val service = service(trials = listOf(trial()), status = TestSpecRunStatus.RUNNING)

        assertFailsWith<AnalysisRequestException> { service.createForTestSpecRun(RUN_ID) }
    }

    @Test
    fun `refuses a run that does not exist`() {
        val service = service(trials = emptyList(), runExists = false)

        assertFailsWith<TestSpecRunNotFoundException> { service.createForTestSpecRun(RUN_ID) }
    }

    /**
     * "We could not judge this" is exactly the kind of finding a suggestion should see, so an inconclusive run is
     * analyzable. Only an unfinished or unverified one is refused.
     */
    @Test
    fun `analyzes an inconclusive run`() {
        val service = service(trials = listOf(trial(outcome = TrialOutcome.INCONCLUSIVE)))

        service.createForTestSpecRun(RUN_ID)

        assertTrue(datasets.created.single().evidenceBundleJson.contains("INCONCLUSIVE"))
    }

    private fun service(
        trials: List<StoredTrialResult>,
        cleanupVerified: Boolean = true,
        status: TestSpecRunStatus = TestSpecRunStatus.COMPLETED,
        runExists: Boolean = true,
    ) = AnalysisDatasetService(
        experimentRepository = EmptyExperimentSource(),
        targetTestBatchRepository = EmptyBatchSource(),
        testSpecRunRepository = StubSpecRunSource(
            run = if (runExists) specRun(status, cleanupVerified) else null,
            trials = trials,
        ),
        datasetRepository = datasets,
        objectMapper = ObjectMapper(),
        properties = FixedSettings(),
        clock = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC),
        identifierGenerator = { UUID.randomUUID() },
    )

    private fun specRun(status: TestSpecRunStatus, cleanupVerified: Boolean) = TestSpecRun(
        id = RUN_ID,
        specificationId = UUID.randomUUID(),
        targetSystemId = "sideproject",
        profileVersionId = UUID.randomUUID(),
        status = status,
        idempotencyKey = "key-1",
        requestHash = "hash-1",
        requestedTrials = 1,
        createdBy = "tester",
        createdCorrelationId = "correlation-1",
        createdAt = Instant.parse("2026-08-21T00:00:00Z"),
        resultOutcome = TrialOutcome.VIOLATED,
        trialsRun = 1,
        trialsViolated = 1,
        trialsInconclusive = 0,
        cleanupVerified = cleanupVerified,
        startedAt = Instant.parse("2026-08-21T00:00:01Z"),
        completedAt = Instant.parse("2026-08-21T00:00:09Z"),
        failure = null,
    )

    private fun trial(
        number: Int = 1,
        outcome: TrialOutcome = TrialOutcome.VIOLATED,
    ) = StoredTrialResult(
        runId = RUN_ID,
        trialNumber = number,
        outcome = outcome,
        stateChanged = true,
        completed = true,
        failure = null,
        verdicts = listOf(
            InvariantVerdict(
                invariantId = "deduction-follows-promptly",
                description = "예약 후 DB 반영이 지나치게 늦으면 안 된다",
                outcome = InvariantOutcome.VIOLATED,
                condition = "maxStartLagMs(reserveSpans, deductSpans) <= 100",
                observedValues = mapOf("reserveSpans" to "[...] (2 spans across 2 traces)"),
            ),
        ),
        timings = emptyList(),
        observations = mapOf(
            "reserveSpans" to ObservedEvidence(
                present = true,
                display = "[...] (2 spans across 2 traces)",
                value = listOf(
                    mapOf("traceId" to "trace-7", "name" to "inventory.reserve", "startMs" to 1_000L),
                    mapOf("traceId" to "trace-9", "name" to "inventory.reserve", "startMs" to 1_340L),
                ),
            ),
        ),
    )

    private class StubSpecRunSource(
        private val run: TestSpecRun?,
        private val trials: List<StoredTrialResult>,
    ) : TestSpecRunAnalysisEvidenceSource {
        override fun findTestSpecRun(id: UUID): TestSpecRun? = run
        override fun findTestSpecTrials(runId: UUID): List<StoredTrialResult> = trials
    }

    private class RecordingDatasetStore : AnalysisDatasetStore {
        val created = mutableListOf<NewAnalysisDataset>()

        override fun create(dataset: NewAnalysisDataset) {
            created.add(dataset)
        }

        override fun findById(id: UUID): AnalysisDatasetRecord? = created.firstOrNull { it.id == id }?.let {
            AnalysisDatasetRecord(
                id = it.id,
                experimentRunId = it.experimentRunId,
                targetTestBatchId = it.targetTestBatchId,
                testSpecRunId = it.testSpecRunId,
                contractVersion = it.contractVersion,
                evidenceBundleJson = it.evidenceBundleJson,
                evidenceIds = it.evidenceIds,
                checksum = it.checksum,
                evidenceCount = it.evidenceIds.size,
                createdAt = it.createdAt,
            )
        }

        override fun createGroundTruth(groundTruth: NewAnalysisGroundTruth) = Unit
        override fun findGroundTruth(id: UUID): AnalysisGroundTruthRecord? = null
    }

    private class EmptyExperimentSource : ExperimentAnalysisEvidenceSource {
        override fun findExperimentRun(id: UUID): ExperimentRunRecord? = null
        override fun findExperimentEvidence(runId: UUID): List<ExperimentEvidenceRecord> = emptyList()
    }

    private class EmptyBatchSource : TargetTestBatchAnalysisEvidenceSource {
        override fun findTargetTestBatch(id: UUID): TargetTestBatchRecord? = null
        override fun findTargetTestBatchItems(batchId: UUID): List<TargetTestBatchItemRecord> = emptyList()
    }

    private class FixedSettings : ReliabilityAgentSettings {
        override val enabled = true
        override val defaultModelKey = "test-model"
        override val promptVersion = "v1"
        override val maxEvidenceCount = 100
        override val maxEvidenceBytes = 1_000_000
        override val maxOutputBytes = 100_000
    }

    private companion object {
        val RUN_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    }
}
