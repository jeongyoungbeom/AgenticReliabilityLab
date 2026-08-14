package com.project.agenticreliabilitylab.analysis.api

import com.project.agenticreliabilitylab.analysis.api.dto.AnalysisRunDetailsResponse
import com.project.agenticreliabilitylab.analysis.api.dto.AnalysisRunResponse
import com.project.agenticreliabilitylab.analysis.api.dto.CreateAnalysisRequest
import com.project.agenticreliabilitylab.analysis.application.SingleReliabilityAgent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class AnalysisController(
    private val agent: SingleReliabilityAgent,
) {
    @PostMapping("/api/experiments/{experimentRunId}/analyses")
    fun start(
        @PathVariable experimentRunId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateAnalysisRequest?,
    ): ResponseEntity<AnalysisRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(AnalysisRunResponse.from(agent.start(experimentRunId, idempotencyKey, request?.modelKey)))
    }

    @PostMapping("/api/test-batches/{targetTestBatchId}/analyses")
    fun startForTargetTestBatch(
        @PathVariable targetTestBatchId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateAnalysisRequest?,
    ): ResponseEntity<AnalysisRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(AnalysisRunResponse.from(agent.startForTargetTestBatch(targetTestBatchId, idempotencyKey, request?.modelKey)))
    }

    @GetMapping("/api/analysis-runs/{analysisRunId}")
    fun find(@PathVariable analysisRunId: UUID): AnalysisRunDetailsResponse =
        AnalysisRunDetailsResponse.from(agent.find(analysisRunId))

}
