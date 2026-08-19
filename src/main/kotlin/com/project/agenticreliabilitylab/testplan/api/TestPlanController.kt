package com.project.agenticreliabilitylab.testplan.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.testplan.api.dto.ApproveTestPlanRequest
import com.project.agenticreliabilitylab.testplan.api.dto.CreateTestPlanRequest
import com.project.agenticreliabilitylab.testplan.api.dto.TestPlanResponse
import com.project.agenticreliabilitylab.testplan.application.TestPlanService
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
 * Phase 13 selection, approval and dispatch.
 *
 * Approval and dispatch are separate calls on purpose: a plan that is approved but not yet dispatched is a real state,
 * and it is re-checked against the active Profile before any work is handed over.
 */
@RestController
@RequestMapping("/api/test-plans")
class TestPlanController(
    private val service: TestPlanService,
    private val operatorAccessService: OperatorAccessService,
) {
    @PostMapping
    fun create(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateTestPlanRequest,
    ): ResponseEntity<TestPlanResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val actor = operatorAccessService.requireExecutor(authorization)
        val view = service.create(request.toCommand(), idempotencyKey, actor, correlationId())
        return ResponseEntity.status(HttpStatus.CREATED).body(TestPlanResponse.from(view))
    }

    @PostMapping("/{planId}/approve")
    fun approve(
        @PathVariable planId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: ApproveTestPlanRequest,
    ): TestPlanResponse {
        val actor = operatorAccessService.requireExecutor(authorization)
        return TestPlanResponse.from(service.approve(planId, request.confirmation, actor, correlationId()))
    }

    @PostMapping("/{planId}/dispatch")
    fun dispatch(
        @PathVariable planId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<TestPlanResponse> {
        val actor = operatorAccessService.requireExecutor(authorization)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(TestPlanResponse.from(service.dispatch(planId, actor, correlationId())))
    }

    @GetMapping("/{planId}")
    fun find(
        @PathVariable planId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TestPlanResponse {
        operatorAccessService.requireViewer(authorization)
        return TestPlanResponse.from(service.find(planId))
    }

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
