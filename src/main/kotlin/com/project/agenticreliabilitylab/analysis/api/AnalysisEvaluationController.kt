package com.project.agenticreliabilitylab.analysis.api

import com.project.agenticreliabilitylab.analysis.api.dto.AnalysisComparisonResponse
import com.project.agenticreliabilitylab.analysis.api.dto.AnalysisEvaluationResponse
import com.project.agenticreliabilitylab.analysis.api.dto.AnalysisGroundTruthResponse
import com.project.agenticreliabilitylab.analysis.api.dto.CreateAnalysisComparisonRequest
import com.project.agenticreliabilitylab.analysis.api.dto.CreateGroundTruthRequest
import com.project.agenticreliabilitylab.analysis.api.dto.toRequestedConfigurations
import com.project.agenticreliabilitylab.analysis.application.AnalysisComparisonService
import com.project.agenticreliabilitylab.analysis.application.AnalysisEvaluationService
import com.project.agenticreliabilitylab.analysis.application.AnalysisRequestException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class AnalysisEvaluationController(
    private val comparisonService: AnalysisComparisonService,
    private val evaluationService: AnalysisEvaluationService,
) {
    @PostMapping("/api/experiments/{experimentRunId}/analysis-comparisons")
    fun startComparison(
        @PathVariable experimentRunId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateAnalysisComparisonRequest?,
    ): ResponseEntity<AnalysisComparisonResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val comparison = comparisonService.start(experimentRunId, idempotencyKey, request.toRequestedConfigurations())
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AnalysisComparisonResponse.from(comparison))
    }

    @PostMapping("/api/test-batches/{targetTestBatchId}/analysis-comparisons")
    fun startComparisonForTargetTestBatch(
        @PathVariable targetTestBatchId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateAnalysisComparisonRequest?,
    ): ResponseEntity<AnalysisComparisonResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val comparison = comparisonService.startForTargetTestBatch(targetTestBatchId, idempotencyKey, request.toRequestedConfigurations())
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AnalysisComparisonResponse.from(comparison))
    }

    @GetMapping("/api/analysis-comparisons/{comparisonId}")
    fun findComparison(@PathVariable comparisonId: UUID): AnalysisComparisonResponse =
        AnalysisComparisonResponse.from(comparisonService.find(comparisonId))

    @PostMapping("/api/analysis-datasets/{analysisDatasetId}/ground-truth")
    fun createGroundTruth(
        @PathVariable analysisDatasetId: UUID,
        @Valid @RequestBody request: CreateGroundTruthRequest,
    ): ResponseEntity<AnalysisGroundTruthResponse> = ResponseEntity.status(HttpStatus.CREATED).body(
        AnalysisGroundTruthResponse.from(
            evaluationService.createGroundTruth(
                analysisDatasetId = analysisDatasetId,
                version = request.version,
                expectedVerdict = request.expectedVerdict,
                requiredEvidenceIds = request.requiredEvidenceIds,
                notes = request.notes,
            ),
        ),
    )

    @PostMapping("/api/analysis-runs/{analysisRunId}/evaluations/{groundTruthId}")
    fun evaluate(
        @PathVariable analysisRunId: UUID,
        @PathVariable groundTruthId: UUID,
    ): ResponseEntity<AnalysisEvaluationResponse> = ResponseEntity.status(HttpStatus.CREATED)
        .body(AnalysisEvaluationResponse.from(evaluationService.evaluate(analysisRunId, groundTruthId)))

    @PostMapping("/api/analysis-comparisons/{comparisonId}/evaluations/{groundTruthId}")
    fun evaluateComparison(
        @PathVariable comparisonId: UUID,
        @PathVariable groundTruthId: UUID,
    ): List<AnalysisEvaluationResponse> {
        val comparison = comparisonService.find(comparisonId)
        if (comparison.runs.any { it.details.run.status != com.project.agenticreliabilitylab.analysis.domain.AnalysisRunStatus.COMPLETED }) {
            throw AnalysisRequestException("COMPARISON_NOT_EVALUABLE", "Every analysis run in the comparison must be COMPLETED before evaluation")
        }
        return comparison.runs.map { run ->
        AnalysisEvaluationResponse.from(evaluationService.evaluate(run.mapping.analysisRunId, groundTruthId))
        }
    }

}
