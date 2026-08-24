package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.testspec.api.dto.ReportTestSpecMisjudgmentRequest
import com.project.agenticreliabilitylab.testspec.api.dto.TestSpecMisjudgmentReportResponse
import com.project.agenticreliabilitylab.testspec.application.TestSpecMisjudgmentReportService
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
 * Phase 22-C: a reviewer's misjudgment claim drafts a narrow exception through the same validator gate as any
 * other specification.
 */
@RestController
@RequestMapping("/api")
class TestSpecMisjudgmentReportController(
    private val service: TestSpecMisjudgmentReportService,
    private val operatorAccessService: OperatorAccessService,
) {
    @PostMapping("/targets/{targetSystemId}/test-spec-misjudgment-reports")
    fun report(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: ReportTestSpecMisjudgmentRequest,
    ): ResponseEntity<TestSpecMisjudgmentReportResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val actor = operatorAccessService.requireProfileEditor(authorization)
        val report = service.report(request.toCommand(targetSystemId), idempotencyKey, actor, correlationId())
        val body = TestSpecMisjudgmentReportResponse.from(report)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body)
    }

    @GetMapping("/test-spec-misjudgment-reports/{reportId}")
    fun find(
        @PathVariable reportId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TestSpecMisjudgmentReportResponse {
        operatorAccessService.requireViewer(authorization)
        return TestSpecMisjudgmentReportResponse.from(service.find(reportId))
    }

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
