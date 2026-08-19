package com.project.agenticreliabilitylab.targetintelligence.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetintelligence.api.dto.ConfirmTargetKnowledgeSnapshotRequest
import com.project.agenticreliabilitylab.targetintelligence.api.dto.CreateTargetKnowledgeSnapshotRequest
import com.project.agenticreliabilitylab.targetintelligence.api.dto.TargetKnowledgeSnapshotResponse
import com.project.agenticreliabilitylab.targetintelligence.application.TargetKnowledgeSnapshotService
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
 * Phase 11 document intake and review.
 *
 * Every endpoint reads supplied documents or stored Snapshots only. None of them registers a Target, activates a
 * Profile, creates an executable candidate or sends a request to the Target.
 */
@RestController
@RequestMapping("/api")
class TargetKnowledgeSnapshotController(
    private val service: TargetKnowledgeSnapshotService,
    private val operatorAccessService: OperatorAccessService,
) {
    @PostMapping("/target-knowledge-snapshots")
    fun create(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: CreateTargetKnowledgeSnapshotRequest,
    ): ResponseEntity<TargetKnowledgeSnapshotResponse> {
        val actor = operatorAccessService.requireProfileEditor(authorization)
        val view = service.create(request.toCommand(), actor, correlationId())
        return ResponseEntity.status(HttpStatus.CREATED).body(TargetKnowledgeSnapshotResponse.from(view))
    }

    @GetMapping("/target-knowledge-snapshots/{snapshotId}")
    fun find(
        @PathVariable snapshotId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TargetKnowledgeSnapshotResponse {
        operatorAccessService.requireViewer(authorization)
        return TargetKnowledgeSnapshotResponse.from(service.find(snapshotId))
    }

    @GetMapping("/targets/{targetSystemId}/knowledge-snapshots")
    fun findByTarget(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<TargetKnowledgeSnapshotResponse> {
        operatorAccessService.requireViewer(authorization)
        return service.findByTarget(targetSystemId).map(TargetKnowledgeSnapshotResponse::from)
    }

    @PostMapping("/target-knowledge-snapshots/{snapshotId}/confirmation")
    fun confirm(
        @PathVariable snapshotId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: ConfirmTargetKnowledgeSnapshotRequest,
    ): TargetKnowledgeSnapshotResponse {
        require(request.confirmation == REVIEW_CONFIRMATION) { "confirmation must equal $REVIEW_CONFIRMATION" }
        val actor = operatorAccessService.requireProfileEditor(authorization)
        return TargetKnowledgeSnapshotResponse.from(service.confirm(snapshotId, actor, correlationId()))
    }

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val REVIEW_CONFIRMATION = "CONFIRM_TARGET_KNOWLEDGE"
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
