package com.project.agenticreliabilitylab.targetdiscovery.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetdiscovery.application.ExecutePilotTemplates
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotTemplateExecutionService
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotTestSessionView
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItemStatus
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import com.project.agenticreliabilitylab.targetcredential.api.TargetCredentialSessionCookie
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class ExecutePilotTemplatesRequest(
    @field:NotEmpty
    @field:Size(max = 7)
    val candidateIds: List<@NotBlank String>,
    @field:NotBlank
    val confirmation: String,
)

data class PilotTestSessionResponse(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val status: PilotTestSessionStatus,
    val resultOutcome: TrialOutcome?,
    val cleanupVerified: Boolean?,
    val createdAt: Instant,
    val completedAt: Instant?,
    val failure: String?,
    val outcomes: List<PilotTestSessionOutcomeResponse>,
) {
    companion object {
        fun from(view: PilotTestSessionView) = PilotTestSessionResponse(
            id = view.session.id,
            targetSystemId = view.session.targetSystemId,
            profileVersionId = view.session.profileVersionId,
            status = view.session.status,
            resultOutcome = view.session.resultOutcome,
            cleanupVerified = view.session.cleanupVerified,
            createdAt = view.session.createdAt,
            completedAt = view.session.completedAt,
            failure = view.session.failure,
            outcomes = view.items.map { item ->
                PilotTestSessionOutcomeResponse(
                    candidateId = item.candidateId,
                    specificationId = item.specificationId,
                    testSpecRunId = item.testSpecRunId,
                    status = item.status,
                    resultOutcome = item.resultOutcome,
                    cleanupVerified = item.cleanupVerified,
                    failureCode = item.failureCode,
                    failureMessage = item.failureMessage,
                    completedAt = item.completedAt,
                )
            },
        )
    }
}

data class PilotTestSessionOutcomeResponse(
    val candidateId: String,
    val specificationId: UUID?,
    val testSpecRunId: UUID?,
    val status: PilotTestSessionItemStatus,
    val resultOutcome: TrialOutcome?,
    val cleanupVerified: Boolean?,
    val failureCode: String?,
    val failureMessage: String?,
    val completedAt: Instant,
)

@RestController
class PilotTemplateExecutionController(
    private val service: PilotTemplateExecutionService,
    private val access: OperatorAccessService,
) {
    @PostMapping("/api/targets/{targetSystemId}/pilot-template-runs")
    fun execute(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
        @Valid @RequestBody request: ExecutePilotTemplatesRequest,
    ): ResponseEntity<PilotTestSessionResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val actor = access.requireExecutor(authorization)
        val session = service.execute(
            ExecutePilotTemplates(
                targetSystemId, request.candidateIds, request.confirmation, idempotencyKey, credentialSessionId,
            ),
            actor,
            MDC.get("correlationId") ?: "missing-correlation-id",
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(PilotTestSessionResponse.from(session))
    }

    @GetMapping("/api/targets/{targetSystemId}/pilot-test-sessions")
    fun findByTarget(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<PilotTestSessionResponse> {
        access.requireViewer(authorization)
        return service.findSessions(targetSystemId).map(PilotTestSessionResponse::from)
    }

    @GetMapping("/api/pilot-test-sessions/{sessionId}")
    fun find(
        @PathVariable sessionId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): PilotTestSessionResponse {
        access.requireViewer(authorization)
        return PilotTestSessionResponse.from(service.findSession(sessionId))
    }

}
