package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.testspec.api.dto.ApproveTestSpecificationRequest
import com.project.agenticreliabilitylab.testspec.api.dto.CreateTestSpecificationRequest
import com.project.agenticreliabilitylab.testspec.api.dto.TestSpecRegressionRunsResponse
import com.project.agenticreliabilitylab.testspec.api.dto.TestSpecRunResponse
import com.project.agenticreliabilitylab.testspec.api.dto.TestSpecificationResponse
import com.project.agenticreliabilitylab.testspec.application.TestSpecificationService
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
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Phase 17 specification registration, approval and deterministic execution API. */
@RestController
@RequestMapping("/api")
class TestSpecificationController(
    private val service: TestSpecificationService,
    private val operatorAccessService: OperatorAccessService,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping("/test-specifications")
    fun create(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: CreateTestSpecificationRequest,
    ): ResponseEntity<TestSpecificationResponse> {
        val actor = operatorAccessService.requireProfileEditor(authorization)
        val view = service.create(request.toCommand(objectMapper), actor, correlationId())
        return ResponseEntity.status(HttpStatus.CREATED).body(TestSpecificationResponse.from(view, objectMapper))
    }

    @PostMapping("/test-specifications/{specificationId}/approve")
    fun approve(
        @PathVariable specificationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: ApproveTestSpecificationRequest,
    ): TestSpecificationResponse {
        val actor = operatorAccessService.requireExecutor(authorization)
        val view = service.approve(specificationId, request.confirmation, actor, correlationId())
        return TestSpecificationResponse.from(view, objectMapper)
    }

    @PostMapping("/test-specifications/{specificationId}/runs")
    fun execute(
        @PathVariable specificationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestHeader(CREDENTIAL_SESSION_HEADER, required = false) credentialSessionId: String?,
    ): ResponseEntity<TestSpecRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val actor = operatorAccessService.requireExecutor(authorization)
        val view = service.execute(specificationId, idempotencyKey, actor, correlationId(), credentialSessionId)
        return ResponseEntity.status(HttpStatus.CREATED).body(TestSpecRunResponse.from(view))
    }

    @PostMapping("/targets/{targetSystemId}/test-specifications/regression-runs")
    fun triggerRegressionRuns(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestHeader(CREDENTIAL_SESSION_HEADER, required = false) credentialSessionId: String?,
    ): TestSpecRegressionRunsResponse {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val actor = operatorAccessService.requireExecutor(authorization)
        val outcomes = service.triggerRegressionRuns(
            targetSystemId,
            idempotencyKey,
            actor,
            correlationId(),
            credentialSessionId,
        )
        return TestSpecRegressionRunsResponse.from(targetSystemId, outcomes)
    }

    @GetMapping("/test-specifications/{specificationId}")
    fun findSpecification(
        @PathVariable specificationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TestSpecificationResponse {
        operatorAccessService.requireViewer(authorization)
        return TestSpecificationResponse.from(service.findSpecification(specificationId), objectMapper)
    }

    @GetMapping("/targets/{targetSystemId}/test-specifications")
    fun findByTarget(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<TestSpecificationResponse> {
        operatorAccessService.requireViewer(authorization)
        return service.findByTarget(targetSystemId).map { view -> TestSpecificationResponse.from(view, objectMapper) }
    }

    @GetMapping("/test-spec-runs/{runId}")
    fun findRun(
        @PathVariable runId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TestSpecRunResponse {
        operatorAccessService.requireViewer(authorization)
        return TestSpecRunResponse.from(service.findRun(runId))
    }

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val CORRELATION_ID_KEY = "correlationId"
        const val CREDENTIAL_SESSION_HEADER = "X-ARL-Target-Credential-Session"
    }
}
