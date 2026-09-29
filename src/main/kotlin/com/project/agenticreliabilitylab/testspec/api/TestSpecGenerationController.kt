package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.testspec.api.dto.StartTestSpecGenerationRequest
import com.project.agenticreliabilitylab.testspec.api.dto.TestSpecGenerationRunResponse
import com.project.agenticreliabilitylab.testspec.application.TestSpecGenerationService
import com.project.agenticreliabilitylab.targetcredential.api.TargetCredentialSessionCookie
import org.springframework.web.bind.annotation.CookieValue
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

/** Model proposals beside the confirmed Snapshot and basic-candidate flow. */
@RestController
@RequestMapping("/api")
class TestSpecGenerationController(
    private val service: TestSpecGenerationService,
    private val operatorAccessService: OperatorAccessService,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping("/targets/{targetSystemId}/test-specification-generations")
    fun start(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
        @Valid @RequestBody request: StartTestSpecGenerationRequest,
    ): ResponseEntity<TestSpecGenerationRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val actor = operatorAccessService.requireProfileEditor(authorization)
        val details = service.start(
            request.toCommand(targetSystemId, credentialSessionId),
            idempotencyKey,
            actor,
            correlationId(),
        )
        val body = TestSpecGenerationRunResponse.from(details, objectMapper)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body)
    }

    @GetMapping("/test-specification-generations/{runId}")
    fun find(
        @PathVariable runId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TestSpecGenerationRunResponse {
        operatorAccessService.requireViewer(authorization)
        return TestSpecGenerationRunResponse.from(service.find(runId), objectMapper)
    }

    @GetMapping("/targets/{targetSystemId}/test-specification-generations")
    fun findByTarget(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<TestSpecGenerationRunResponse> {
        operatorAccessService.requireViewer(authorization)
        return service.findByTarget(targetSystemId).map { TestSpecGenerationRunResponse.from(it, objectMapper) }
    }

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
