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
