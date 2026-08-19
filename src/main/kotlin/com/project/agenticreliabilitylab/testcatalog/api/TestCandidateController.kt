package com.project.agenticreliabilitylab.testcatalog.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.testcatalog.api.dto.CreateTestCandidateGenerationRequest
import com.project.agenticreliabilitylab.testcatalog.api.dto.RequestTestCandidateRequest
import com.project.agenticreliabilitylab.testcatalog.api.dto.TestCandidateGenerationResponse
import com.project.agenticreliabilitylab.testcatalog.api.dto.TestCandidateGenerationSummaryResponse
import com.project.agenticreliabilitylab.testcatalog.application.TestCandidateService
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

/**
 * Phase 12 candidate recommendation.
 *
 * Recommending a test never runs it. These endpoints read a stored Knowledge Snapshot and the active Profile, and they
 * cannot create a Batch, approve anything or send a request to the Target.
 */
@RestController
@RequestMapping("/api")
class TestCandidateController(
    private val service: TestCandidateService,
    private val operatorAccessService: OperatorAccessService,
) {
    @PostMapping("/test-candidate-generations")
    fun generate(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: CreateTestCandidateGenerationRequest,
    ): ResponseEntity<TestCandidateGenerationResponse> {
        val actor = operatorAccessService.requireProfileEditor(authorization)
        val snapshotId = requireNotNull(request.knowledgeSnapshotId) { "knowledgeSnapshotId is required" }
        val view = service.generate(snapshotId, actor, correlationId())
        return ResponseEntity.status(HttpStatus.CREATED).body(TestCandidateGenerationResponse.from(view))
    }

    @PostMapping("/test-candidate-requests")
    fun request(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: RequestTestCandidateRequest,
    ): ResponseEntity<TestCandidateGenerationResponse> {
        val actor = operatorAccessService.requireProfileEditor(authorization)
        val view = service.request(request.toCommand(), actor, correlationId())
        return ResponseEntity.status(HttpStatus.CREATED).body(TestCandidateGenerationResponse.from(view))
    }

    @GetMapping("/test-candidate-generations/{generationId}")
    fun find(
        @PathVariable generationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TestCandidateGenerationResponse {
        operatorAccessService.requireViewer(authorization)
        return TestCandidateGenerationResponse.from(service.find(generationId))
    }

    @GetMapping("/targets/{targetSystemId}/test-candidate-generations")
    fun findByTarget(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<TestCandidateGenerationSummaryResponse> {
        operatorAccessService.requireViewer(authorization)
        return service.findByTarget(targetSystemId).map(TestCandidateGenerationSummaryResponse::from)
    }

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
