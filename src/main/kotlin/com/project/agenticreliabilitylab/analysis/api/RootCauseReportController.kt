package com.project.agenticreliabilitylab.analysis.api

import com.project.agenticreliabilitylab.analysis.api.dto.CreateRootCauseReportRequest
import com.project.agenticreliabilitylab.analysis.api.dto.RootCauseReportResponse
import com.project.agenticreliabilitylab.analysis.application.RootCauseReportService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Phase 9 exposes evidence-grounded advice only; it has no approval or implementation API. */
@RestController
class RootCauseReportController(private val service: RootCauseReportService) {
    @PostMapping("/api/analysis-runs/{analysisRunId}/root-cause-reports")
    fun start(
        @PathVariable analysisRunId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: CreateRootCauseReportRequest?,
    ): ResponseEntity<RootCauseReportResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            RootCauseReportResponse.from(service.start(analysisRunId, idempotencyKey, request?.modelKey)),
        )
    }

    @GetMapping("/api/root-cause-reports/{reportId}")
    fun find(@PathVariable reportId: UUID): RootCauseReportResponse = RootCauseReportResponse.from(service.find(reportId))

}
