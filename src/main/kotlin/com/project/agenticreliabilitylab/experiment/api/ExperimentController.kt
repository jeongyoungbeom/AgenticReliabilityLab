package com.project.agenticreliabilitylab.experiment.api

import com.project.agenticreliabilitylab.experiment.api.dto.CreateExperimentRequest
import com.project.agenticreliabilitylab.experiment.api.dto.ExperimentEvidenceResponse
import com.project.agenticreliabilitylab.experiment.api.dto.ExperimentRunResponse
import com.project.agenticreliabilitylab.experiment.application.StartStockConcurrencyExperiment
import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyExperimentService
import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import jakarta.validation.Valid
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
@RequestMapping("/api/experiments")
class ExperimentController(
    private val service: StockConcurrencyExperimentService,
) {
    @PostMapping
    fun start(
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateExperimentRequest,
    ): ResponseEntity<ExperimentRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        require(!request.targetSystem.isNullOrBlank()) { "targetSystem is required" }
        require(request.type == ExperimentType.STOCK_CONCURRENCY) {
            "Only type STOCK_CONCURRENCY is available in Phase 1"
        }
        val parameters = request.parameters ?: throw IllegalArgumentException("parameters is required")
        val run = service.start(
            StartStockConcurrencyExperiment(request.targetSystem, parameters),
            idempotencyKey,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ExperimentRunResponse.from(run))
    }

    @GetMapping("/{id}")
    fun find(@PathVariable id: UUID): ExperimentRunResponse =
        ExperimentRunResponse.from(service.find(id))

    @GetMapping("/{id}/evidence")
    fun evidence(@PathVariable id: UUID): List<ExperimentEvidenceResponse> =
        service.evidence(id).map(ExperimentEvidenceResponse::from)

}
