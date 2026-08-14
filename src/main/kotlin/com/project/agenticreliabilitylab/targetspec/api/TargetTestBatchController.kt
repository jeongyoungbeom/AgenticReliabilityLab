package com.project.agenticreliabilitylab.targetspec.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetspec.api.dto.ApproveTargetTestBatchRequest
import com.project.agenticreliabilitylab.targetspec.api.dto.CreateTargetTestBatchRequest
import com.project.agenticreliabilitylab.targetspec.api.dto.TargetTestBatchResponse
import com.project.agenticreliabilitylab.targetspec.api.dto.TargetTestCandidateResponse
import com.project.agenticreliabilitylab.targetspec.application.CreateTargetTestBatch
import com.project.agenticreliabilitylab.targetspec.application.TargetTestBatchService
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import jakarta.validation.Valid
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class TargetTestBatchController(
    private val service: TargetTestBatchService,
    private val operatorAccessService: OperatorAccessService,
) {
    @GetMapping("/targets/{targetSystemId}/test-candidates")
    fun candidates(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<TargetTestCandidateResponse> {
        operatorAccessService.requireViewer(authorization)
        return service.candidates(targetSystemId).map(TargetTestCandidateResponse::from)
    }

    @PostMapping("/test-batches")
    fun create(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateTargetTestBatchRequest,
    ): ResponseEntity<TargetTestBatchResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        operatorAccessService.requireExecutor(authorization)
        val batch = service.create(
            CreateTargetTestBatch(request.targetSystemId, request.candidateIds),
            idempotencyKey,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response(batch))
    }

    @PostMapping("/test-batches/{batchId}/approve")
    fun approve(
        @PathVariable batchId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: ApproveTargetTestBatchRequest,
    ): ResponseEntity<TargetTestBatchResponse> {
        require(request.confirmation == APPROVAL_CONFIRMATION) {
            "confirmation must equal $APPROVAL_CONFIRMATION"
        }
        val actor = operatorAccessService.requireExecutor(authorization)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            response(service.approve(batchId, actor, correlationId())),
        )
    }

    @GetMapping("/test-batches/{batchId}")
    fun find(
        @PathVariable batchId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TargetTestBatchResponse {
        operatorAccessService.requireViewer(authorization)
        return response(service.find(batchId))
    }

    private fun response(batch: TargetTestBatchRecord): TargetTestBatchResponse =
        TargetTestBatchResponse.from(batch, service.items(batch.id))

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val APPROVAL_CONFIRMATION = "EXECUTE_SAFE_HTTP_BATCH"
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
