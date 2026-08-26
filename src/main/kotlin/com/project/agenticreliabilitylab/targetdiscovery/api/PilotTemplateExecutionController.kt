package com.project.agenticreliabilitylab.targetdiscovery.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetdiscovery.application.ExecutePilotTemplates
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotTemplateExecution
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotTemplateExecutionService
import com.project.agenticreliabilitylab.testspec.api.dto.TestSpecRunResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ExecutePilotTemplatesRequest(
    @field:NotEmpty
    @field:Size(max = 7)
    val candidateIds: List<@NotBlank String>,
    @field:NotBlank
    val confirmation: String,
)

data class PilotTemplateExecutionResponse(
    val targetSystemId: String,
    val outcomes: List<PilotTemplateExecutionOutcomeResponse>,
) {
    companion object {
        fun from(execution: PilotTemplateExecution) = PilotTemplateExecutionResponse(
            targetSystemId = execution.targetSystemId,
            outcomes = execution.outcomes.map { outcome ->
                PilotTemplateExecutionOutcomeResponse(
                    candidateId = outcome.candidateId,
                    specificationId = outcome.specificationId,
                    run = outcome.run?.let(TestSpecRunResponse::from),
                    failureCode = outcome.failureCode,
                    failureMessage = outcome.failureMessage,
                )
            },
        )
    }
}

data class PilotTemplateExecutionOutcomeResponse(
    val candidateId: String,
    val specificationId: UUID?,
    val run: TestSpecRunResponse?,
    val failureCode: String?,
    val failureMessage: String?,
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
        @RequestHeader(CREDENTIAL_SESSION_HEADER, required = false) credentialSessionId: String?,
        @Valid @RequestBody request: ExecutePilotTemplatesRequest,
    ): ResponseEntity<PilotTemplateExecutionResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val actor = access.requireExecutor(authorization)
        val execution = service.execute(
            ExecutePilotTemplates(
                targetSystemId, request.candidateIds, request.confirmation, idempotencyKey, credentialSessionId,
            ),
            actor,
            MDC.get("correlationId") ?: "missing-correlation-id",
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(PilotTemplateExecutionResponse.from(execution))
    }

    private companion object {
        const val CREDENTIAL_SESSION_HEADER = "X-ARL-Target-Credential-Session"
    }
}
