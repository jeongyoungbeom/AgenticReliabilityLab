package com.project.agenticreliabilitylab.targetspec.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetspec.api.dto.ApproveFailureInjectionPlanRequest
import com.project.agenticreliabilitylab.targetspec.api.dto.CreateFailureInjectionPlanRequest
import com.project.agenticreliabilitylab.targetspec.api.dto.FailureInjectionCandidateResponse
import com.project.agenticreliabilitylab.targetspec.api.dto.FailureInjectionPlanResponse
import com.project.agenticreliabilitylab.targetspec.application.model.CreateFailureInjectionPlan
import com.project.agenticreliabilitylab.targetspec.application.FailureInjectionPlanService
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
class FailureInjectionPlanController(
    private val service: FailureInjectionPlanService,
    private val operatorAccessService: OperatorAccessService,
) {
    @GetMapping("/targets/{targetSystemId}/failure-injection-candidates")
    fun candidates(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<FailureInjectionCandidateResponse> {
        operatorAccessService.requireViewer(authorization)
        return service.candidates(targetSystemId).map(FailureInjectionCandidateResponse::from)
    }
    @PostMapping("/failure-injection-plans")
    fun create(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @Valid @RequestBody request: CreateFailureInjectionPlanRequest,
    ): ResponseEntity<FailureInjectionPlanResponse> {
        require(!key.isNullOrBlank()) { "Idempotency-Key header is required" }
        operatorAccessService.requireExecutor(authorization)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            FailureInjectionPlanResponse.from(
                service.create(CreateFailureInjectionPlan(request.targetSystemId, request.candidateIds), key),
            ),
        )
    }
    @PostMapping("/failure-injection-plans/{planId}/approve")
    fun approve(
        @PathVariable planId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: ApproveFailureInjectionPlanRequest,
    ): ResponseEntity<FailureInjectionPlanResponse> {
        require(request.confirmation == APPROVAL_CONFIRMATION) { "confirmation must equal $APPROVAL_CONFIRMATION" }
        val actor = operatorAccessService.requireExecutor(authorization)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            FailureInjectionPlanResponse.from(service.approve(planId, actor, correlationId())),
        )
    }
    @GetMapping("/failure-injection-plans/{planId}")
    fun find(
        @PathVariable planId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): FailureInjectionPlanResponse {
        operatorAccessService.requireViewer(authorization)
        return FailureInjectionPlanResponse.from(service.find(planId))
    }
    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val APPROVAL_CONFIRMATION = "APPROVE_FAILURE_INJECTION_PLAN_ONLY"
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
