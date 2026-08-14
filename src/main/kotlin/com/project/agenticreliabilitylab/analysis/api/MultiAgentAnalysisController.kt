package com.project.agenticreliabilitylab.analysis.api

import com.project.agenticreliabilitylab.analysis.api.dto.AnalysisRunResponse
import com.project.agenticreliabilitylab.analysis.api.dto.CreateMultiAgentAnalysisRequest
import com.project.agenticreliabilitylab.analysis.api.dto.MultiAgentAnalysisDetailsResponse
import com.project.agenticreliabilitylab.analysis.application.model.MultiAgentModelSelection
import com.project.agenticreliabilitylab.analysis.application.MultiReliabilityAgent
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
class MultiAgentAnalysisController(
    private val multiAgent: MultiReliabilityAgent,
) {
    @PostMapping("/api/experiments/{experimentRunId}/multi-analyses")
    fun startForExperiment(
        @PathVariable experimentRunId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateMultiAgentAnalysisRequest?,
    ): ResponseEntity<AnalysisRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            AnalysisRunResponse.from(multiAgent.startForExperiment(experimentRunId, idempotencyKey, request?.toSelection() ?: MultiAgentModelSelection())),
        )
    }

    @PostMapping("/api/test-batches/{targetTestBatchId}/multi-analyses")
    fun startForTargetTestBatch(
        @PathVariable targetTestBatchId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateMultiAgentAnalysisRequest?,
    ): ResponseEntity<AnalysisRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            AnalysisRunResponse.from(multiAgent.startForTargetTestBatch(targetTestBatchId, idempotencyKey, request?.toSelection() ?: MultiAgentModelSelection())),
        )
    }

    @GetMapping("/api/multi-analysis-runs/{analysisRunId}")
    fun find(@PathVariable analysisRunId: UUID): MultiAgentAnalysisDetailsResponse =
        MultiAgentAnalysisDetailsResponse.from(multiAgent.findDetails(analysisRunId))
}
