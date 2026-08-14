package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.analysis.domain.AnalysisEvaluationRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisGroundTruthRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunStatus
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisDatasetStore
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisEvaluationStore
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisEvaluation
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisGroundTruth
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class AnalysisEvaluationService(
    private val datasetService: AnalysisDatasetService,
    private val datasetRepository: AnalysisDatasetStore,
    private val evaluationRepository: AnalysisEvaluationStore,
    private val agent: SingleReliabilityAgent,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) {

    @Transactional
    fun createGroundTruth(
        analysisDatasetId: UUID,
        version: String,
        expectedVerdict: AnalysisVerdict,
        requiredEvidenceIds: List<String>,
        notes: String?,
    ): AnalysisGroundTruthRecord {
        val dataset = datasetService.find(analysisDatasetId)
        require(VERSION_PATTERN.matches(version)) { "Ground truth version must contain 1 to 100 letters, numbers, '.', '_' or '-'" }
        require(requiredEvidenceIds.distinct().size == requiredEvidenceIds.size) { "requiredEvidenceIds must not contain duplicates" }
        require(requiredEvidenceIds.all { it in dataset.evidenceIds }) { "Ground truth references evidence outside the immutable dataset" }
        require(notes == null || notes.length <= 2_000) { "Ground truth notes may contain at most 2000 characters" }
        val groundTruth = NewAnalysisGroundTruth(
            id = identifierGenerator.next(),
            analysisDatasetId = dataset.id,
            version = version,
            expectedVerdict = expectedVerdict,
            requiredEvidenceIds = requiredEvidenceIds,
            notes = notes?.trim()?.ifBlank { null },
            createdAt = clock.instant(),
        )
        try {
            datasetRepository.createGroundTruth(groundTruth)
        } catch (_: DuplicateKeyException) {
            throw AnalysisRequestException("GROUND_TRUTH_VERSION_EXISTS", "Ground truth version '$version' already exists for this dataset")
        }
        return datasetRepository.findGroundTruth(groundTruth.id)
            ?: throw IllegalStateException("Created ground truth '${groundTruth.id}' could not be read")
    }

    @Transactional
    fun evaluate(analysisRunId: UUID, groundTruthId: UUID): AnalysisEvaluationRecord {
        val existing = evaluationRepository.findEvaluation(analysisRunId, groundTruthId, EVALUATION_VERSION)
        if (existing != null) return existing

        val details = agent.find(analysisRunId)
        val analysisRun = details.run
        if (analysisRun.status != AnalysisRunStatus.COMPLETED || analysisRun.verdict == null) {
            throw AnalysisRequestException("ANALYSIS_NOT_EVALUABLE", "Only COMPLETED analyses with a verdict may be evaluated")
        }
        val groundTruth = datasetRepository.findGroundTruth(groundTruthId)
            ?: throw AnalysisGroundTruthNotFoundException(groundTruthId)
        if (analysisRun.analysisDatasetId != groundTruth.analysisDatasetId) {
            throw AnalysisRequestException("GROUND_TRUTH_DATASET_MISMATCH", "Analysis and ground truth must use the same immutable dataset")
        }
        val citedEvidenceIds = (details.findings.flatMap { it.evidenceIds } + details.recommendations.flatMap { it.evidenceIds }).toSet()
        val citedRequiredEvidenceCount = groundTruth.requiredEvidenceIds.count { it in citedEvidenceIds }
        val requiredEvidenceCount = groundTruth.requiredEvidenceIds.size
        val citationRecall = if (requiredEvidenceCount == 0) 1.0 else citedRequiredEvidenceCount.toDouble() / requiredEvidenceCount
        val verdictMatch = analysisRun.verdict == groundTruth.expectedVerdict
        val score = (if (verdictMatch) 0.7 else 0.0) + 0.3 * citationRecall
        val evaluation = NewAnalysisEvaluation(
            id = identifierGenerator.next(),
            analysisRunId = analysisRun.id,
            analysisGroundTruthId = groundTruth.id,
            evaluationVersion = EVALUATION_VERSION,
            verdictMatch = verdictMatch,
            citedRequiredEvidenceCount = citedRequiredEvidenceCount,
            requiredEvidenceCount = requiredEvidenceCount,
            citationRecall = citationRecall,
            score = score,
            evaluatedAt = clock.instant(),
        )
        try {
            evaluationRepository.createEvaluation(evaluation)
        } catch (_: DuplicateKeyException) {
            return evaluationRepository.findEvaluation(analysisRunId, groundTruthId, EVALUATION_VERSION)
                ?: throw AnalysisRequestException("EVALUATION_CREATE_RACE", "Could not recover duplicate evaluation")
        }
        return evaluationRepository.findEvaluation(analysisRunId, groundTruthId, EVALUATION_VERSION)
            ?: throw IllegalStateException("Created evaluation '${evaluation.id}' could not be read")
    }

    companion object {
        const val EVALUATION_VERSION = "phase4-v1"
        private val VERSION_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
    }
}

class AnalysisGroundTruthNotFoundException(groundTruthId: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Analysis ground truth", groundTruthId)
