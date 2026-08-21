package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisDatasetStore
import com.project.agenticreliabilitylab.analysis.application.port.ExperimentAnalysisEvidenceSource
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisDataset
import com.project.agenticreliabilitylab.analysis.application.port.TargetTestBatchAnalysisEvidenceSource
import com.project.agenticreliabilitylab.analysis.application.port.ReliabilityAgentSettings
import com.project.agenticreliabilitylab.experiment.application.ExperimentNotFoundException
import com.project.agenticreliabilitylab.experiment.domain.CleanupStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentEvidenceRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunStatus
import com.project.agenticreliabilitylab.targetspec.application.TargetTestBatchNotFoundException
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchStatus
import com.project.agenticreliabilitylab.analysis.application.port.TestSpecRunAnalysisEvidenceSource
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** Creates an immutable, bounded model input snapshot before a model is queued. */
@Service
class AnalysisDatasetService(
    private val experimentRepository: ExperimentAnalysisEvidenceSource,
    private val targetTestBatchRepository: TargetTestBatchAnalysisEvidenceSource,
    private val testSpecRunRepository: TestSpecRunAnalysisEvidenceSource,
    private val datasetRepository: AnalysisDatasetStore,
    private val objectMapper: ObjectMapper,
    private val properties: ReliabilityAgentSettings,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) {

    @Transactional
    fun createForExperiment(experimentRunId: UUID): AnalysisDatasetRecord {
        val experiment = experimentRepository.findExperimentRun(experimentRunId)
            ?: throw ExperimentNotFoundException(experimentRunId.toString())
        requireEligibleExperiment(experiment)
        val evidence = experimentRepository.findExperimentEvidence(experimentRunId)
        val bundle = buildEvidenceBundle(experiment, evidence)
        val dataset = NewAnalysisDataset(
            id = identifierGenerator.next(),
            experimentRunId = experimentRunId,
            contractVersion = EVIDENCE_CONTRACT_VERSION,
            evidenceBundleJson = bundle.json,
            evidenceIds = bundle.evidenceIds,
            checksum = bundle.checksum,
            createdAt = clock.instant(),
        )
        datasetRepository.create(dataset)
        return datasetRepository.findById(dataset.id)
            ?: throw IllegalStateException("Created analysis dataset '${dataset.id}' could not be read")
    }

    /** Builds the same immutable, bounded analysis contract from a completed generic Target HTTP batch. */
    @Transactional
    fun createForTargetTestBatch(targetTestBatchId: UUID): AnalysisDatasetRecord {
        val batch = targetTestBatchRepository.findTargetTestBatch(targetTestBatchId)
            ?: throw TargetTestBatchNotFoundException(targetTestBatchId.toString())
        val items = targetTestBatchRepository.findTargetTestBatchItems(targetTestBatchId)
        requireEligibleTargetTestBatch(batch, items.map { it.status })
        val bundle = buildTargetTestBatchEvidenceBundle(batch, items)
        val dataset = NewAnalysisDataset(
            id = identifierGenerator.next(),
            targetTestBatchId = targetTestBatchId,
            contractVersion = EVIDENCE_CONTRACT_VERSION,
            evidenceBundleJson = bundle.json,
            evidenceIds = bundle.evidenceIds,
            checksum = bundle.checksum,
            createdAt = clock.instant(),
        )
        datasetRepository.create(dataset)
        return datasetRepository.findById(dataset.id)
            ?: throw IllegalStateException("Created analysis dataset '${dataset.id}' could not be read")
    }

    /**
     * Freezes a finished specification run as analysis input.
     *
     * This is the source that carries *why*. The other two say what a system ended up with; this one says which
     * invariant that violated, on what condition, and - when the Target is traced - which requests interleaved and
     * by how much. The spans travel whole rather than as the verdict's rendered summary, because a suggestion has
     * to reason from evidence rather than from a sentence about evidence.
     */
    @Transactional
    fun createForTestSpecRun(testSpecRunId: UUID): AnalysisDatasetRecord {
        val run = testSpecRunRepository.findTestSpecRun(testSpecRunId)
            ?: throw TestSpecRunNotFoundException(testSpecRunId)
        requireEligibleTestSpecRun(run)
        val trials = testSpecRunRepository.findTestSpecTrials(testSpecRunId)
        val bundle = buildTestSpecRunEvidenceBundle(run, trials)
        val dataset = NewAnalysisDataset(
            id = identifierGenerator.next(),
            testSpecRunId = testSpecRunId,
            contractVersion = EVIDENCE_CONTRACT_VERSION,
            evidenceBundleJson = bundle.json,
            evidenceIds = bundle.evidenceIds,
            checksum = bundle.checksum,
            createdAt = clock.instant(),
        )
        datasetRepository.create(dataset)
        return datasetRepository.findById(dataset.id)
            ?: throw IllegalStateException("Created analysis dataset '${dataset.id}' could not be read")
    }

    fun find(datasetId: UUID): AnalysisDatasetRecord = datasetRepository.findById(datasetId)
        ?: throw AnalysisDatasetNotFoundException(datasetId)

    private fun requireEligibleExperiment(experiment: ExperimentRunRecord) {
        if (experiment.runStatus != ExperimentRunStatus.COMPLETED || experiment.cleanupStatus != CleanupStatus.VERIFIED) {
            throw AnalysisRequestException(
                "EXPERIMENT_NOT_ANALYZABLE",
                "Only COMPLETED experiments with VERIFIED cleanup may be analyzed",
            )
        }
    }

    /**
     * Only a finished run may be analyzed, and an unverified cleanup disqualifies it.
     *
     * A run whose cleanup was not verified left the Target in a state nobody confirmed, so the next run's
     * observations may describe leftovers from this one. Reasoning about causes from that is worse than not
     * reasoning at all. `INCONCLUSIVE` is allowed through on purpose: "we could not judge this" is exactly the
     * kind of finding an improvement suggestion should see.
     */
    private fun requireEligibleTestSpecRun(run: TestSpecRun) {
        if (run.status != TestSpecRunStatus.COMPLETED) {
            throw AnalysisRequestException(
                "TEST_SPEC_RUN_NOT_ANALYZABLE",
                "Only COMPLETED specification runs may be analyzed",
            )
        }
        if (run.cleanupVerified != true) {
            throw AnalysisRequestException(
                "TEST_SPEC_RUN_NOT_ANALYZABLE",
                "A specification run whose cleanup was not verified may not be analyzed",
            )
        }
    }

    private fun requireEligibleTargetTestBatch(
        batch: TargetTestBatchRecord,
        itemStatuses: List<TargetTestBatchItemStatus>,
    ) {
        if (batch.status !in setOf(TargetTestBatchStatus.COMPLETED, TargetTestBatchStatus.FAILED)) {
            throw AnalysisRequestException(
                "TARGET_TEST_BATCH_NOT_ANALYZABLE",
                "Only COMPLETED or assertion-FAILED Target test batches may be analyzed",
            )
        }
        if (itemStatuses.isEmpty() || itemStatuses.any { it !in setOf(TargetTestBatchItemStatus.PASSED, TargetTestBatchItemStatus.FAILED) }) {
            throw AnalysisRequestException(
                "TARGET_TEST_BATCH_NOT_ANALYZABLE",
                "Target test batch must have a confirmed result for every selected candidate",
            )
        }
    }

    private fun buildEvidenceBundle(
        experiment: ExperimentRunRecord,
        evidence: List<ExperimentEvidenceRecord>,
    ): EvidenceBundle {
        if (evidence.isEmpty()) throw AnalysisInputException("The completed experiment has no evidence")
        if (evidence.size > properties.maxEvidenceCount) {
            throw AnalysisInputException("Evidence count ${evidence.size} exceeds the configured limit ${properties.maxEvidenceCount}")
        }
        val payload = linkedMapOf<String, Any?>(
            "contractVersion" to EVIDENCE_CONTRACT_VERSION,
            "experiment" to linkedMapOf(
                "id" to experiment.id,
                "targetSystemId" to experiment.targetSystemId,
                "experimentType" to experiment.experimentType.name,
                "runStatus" to experiment.runStatus.name,
                "systemOutcome" to experiment.systemOutcome.name,
                "outcomeReason" to experiment.outcomeReason,
                "parametersJson" to experiment.parametersJson,
                "invariantResultJson" to experiment.invariantResultJson,
                "completedAt" to experiment.completedAt?.toString(),
            ),
            "evidence" to evidence.map {
                linkedMapOf(
                    "id" to it.id,
                    "type" to it.evidenceType,
                    "schemaVersion" to it.schemaVersion,
                    "source" to it.source,
                    "observedAt" to it.observedAt?.toString(),
                    "completeness" to it.completeness,
                    "payloadJson" to it.payloadJson,
                    "artifactRefsJson" to it.artifactRefsJson,
                    "checksum" to it.checksum,
                )
            },
        )
        val json = objectMapper.writeValueAsString(payload)
        val byteSize = json.toByteArray(StandardCharsets.UTF_8).size
        if (byteSize > properties.maxEvidenceBytes) {
            throw AnalysisInputException("Evidence bundle size $byteSize exceeds the configured limit ${properties.maxEvidenceBytes}")
        }
        return EvidenceBundle(json, json.sha256(), evidence.map { it.id.toString() })
    }

    private fun buildTargetTestBatchEvidenceBundle(
        batch: TargetTestBatchRecord,
        items: List<com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord>,
    ): EvidenceBundle {
        if (items.size > properties.maxEvidenceCount) {
            throw AnalysisInputException("Evidence count ${items.size} exceeds the configured limit ${properties.maxEvidenceCount}")
        }
        val evidence = items.map { item ->
            val evidenceId = "target-test-batch:${batch.id}:item:${item.id}"
            linkedMapOf<String, Any?>(
                "id" to evidenceId,
                "type" to "GENERIC_TARGET_HTTP_RESULT",
                "schemaVersion" to "target-test-batch-item-v1",
                "source" to "target-test-batch:${batch.id}",
                "observedAt" to item.completedAt?.toString(),
                "completeness" to "CONFIRMED",
                // resultJson contains status, latency, byte count and a SHA-256 only; never a response body.
                "payloadJson" to item.resultJson,
                "artifactRefsJson" to null,
                "checksum" to item.resultJson?.sha256(),
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "contractVersion" to EVIDENCE_CONTRACT_VERSION,
            "targetTestBatch" to linkedMapOf(
                "id" to batch.id,
                "targetSystemId" to batch.targetSystemId,
                "status" to batch.status.name,
                "failureMessage" to batch.failureMessage,
                "approvedAt" to batch.approvedAt?.toString(),
                "completedAt" to batch.completedAt?.toString(),
            ),
            "evidence" to evidence,
        )
        val json = objectMapper.writeValueAsString(payload)
        val byteSize = json.toByteArray(StandardCharsets.UTF_8).size
        if (byteSize > properties.maxEvidenceBytes) {
            throw AnalysisInputException("Evidence bundle size $byteSize exceeds the configured limit ${properties.maxEvidenceBytes}")
        }
        return EvidenceBundle(json, json.sha256(), evidence.map { it.getValue("id") as String })
    }

    /**
     * One evidence entry per trial, carrying the verdicts and the values they were judged on.
     *
     * The observations travel whole. A verdict's `observedValues` is a rendered summary that keeps five elements,
     * and the whole point of this Phase is that "the deduction landed 340ms after the reservation" needs the spans
     * behind it, not the sentence. Where a trial's observations were dropped for size, the record says so and the
     * omission travels too - a suggestion built on evidence that quietly went missing is worth less than one that
     * knows it is missing something.
     *
     * A run with no trials is not an error here. A run that died before its first trial is itself a finding, and
     * the trial-level `TRIAL_NOT_RUN` verdicts say why.
     */
    private fun buildTestSpecRunEvidenceBundle(run: TestSpecRun, trials: List<StoredTrialResult>): EvidenceBundle {
        if (trials.size > properties.maxEvidenceCount) {
            throw AnalysisInputException(
                "Evidence count ${trials.size} exceeds the configured limit ${properties.maxEvidenceCount}",
            )
        }
        val evidence = trials.map { trial ->
            val payload = objectMapper.writeValueAsString(
                linkedMapOf<String, Any?>(
                    "trialNumber" to trial.trialNumber,
                    "outcome" to trial.outcome.name,
                    "completed" to trial.completed,
                    "stateChanged" to trial.stateChanged,
                    "failure" to trial.failure,
                    "verdicts" to trial.verdicts,
                    "timings" to trial.timings,
                    "observations" to trial.observations,
                ),
            )
            linkedMapOf<String, Any?>(
                "id" to "test-spec-run:${run.id}:trial:${trial.trialNumber}",
                "type" to "TEST_SPEC_TRIAL_RESULT",
                "schemaVersion" to "test-spec-trial-v1",
                "source" to "test-spec-run:${run.id}",
                "observedAt" to run.completedAt?.toString(),
                "completeness" to if (trial.completed) "CONFIRMED" else "PARTIAL",
                "payloadJson" to payload,
                "artifactRefsJson" to null,
                "checksum" to payload.sha256(),
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "contractVersion" to EVIDENCE_CONTRACT_VERSION,
            "testSpecRun" to linkedMapOf(
                "id" to run.id,
                "specificationId" to run.specificationId,
                "targetSystemId" to run.targetSystemId,
                "profileVersionId" to run.profileVersionId,
                "status" to run.status.name,
                "resultOutcome" to run.resultOutcome?.name,
                "trialsRun" to run.trialsRun,
                "trialsViolated" to run.trialsViolated,
                "trialsInconclusive" to run.trialsInconclusive,
                "cleanupVerified" to run.cleanupVerified,
                "failure" to run.failure,
                "completedAt" to run.completedAt?.toString(),
            ),
            "evidence" to evidence,
        )
        val json = objectMapper.writeValueAsString(payload)
        val byteSize = json.toByteArray(StandardCharsets.UTF_8).size
        if (byteSize > properties.maxEvidenceBytes) {
            throw AnalysisInputException(
                "Evidence bundle size $byteSize exceeds the configured limit ${properties.maxEvidenceBytes}",
            )
        }
        return EvidenceBundle(json, json.sha256(), evidence.map { it.getValue("id") as String })
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class EvidenceBundle(
        val json: String,
        val checksum: String,
        val evidenceIds: List<String>,
    )

    companion object {
        const val EVIDENCE_CONTRACT_VERSION = "arl-analysis-evidence-v1"
    }
}

class AnalysisDatasetNotFoundException(datasetId: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Analysis dataset", datasetId)

class AnalysisInputException(message: String) :
    com.project.agenticreliabilitylab.common.ClientRequestException("ANALYSIS_INPUT_INVALID", message)

class TestSpecRunNotFoundException(runId: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Test specification run", runId)
