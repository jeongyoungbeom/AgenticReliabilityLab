package com.project.agenticreliabilitylab.analysis.api

import com.project.agenticreliabilitylab.analysis.api.dto.CreateFollowUpSuggestionRequest
import com.project.agenticreliabilitylab.analysis.api.dto.FollowUpSuggestionRunResponse
import com.project.agenticreliabilitylab.analysis.application.FollowUpSuggestionService
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
class FollowUpSuggestionController(private val service: FollowUpSuggestionService) {
    @PostMapping("/api/analysis-runs/{analysisRunId}/follow-up-test-suggestions")
    fun start(
        @PathVariable analysisRunId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateFollowUpSuggestionRequest?,
    ): ResponseEntity<FollowUpSuggestionRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            FollowUpSuggestionRunResponse.from(service.start(analysisRunId, idempotencyKey, request?.modelKey)),
        )
    }

    @GetMapping("/api/follow-up-test-suggestion-runs/{suggestionRunId}")
    fun find(@PathVariable suggestionRunId: UUID): FollowUpSuggestionRunResponse =
        FollowUpSuggestionRunResponse.from(service.find(suggestionRunId))

}
