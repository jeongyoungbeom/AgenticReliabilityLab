package com.project.agenticreliabilitylab.campaign.api

import com.project.agenticreliabilitylab.campaign.api.dto.CampaignRunDetailResponse
import com.project.agenticreliabilitylab.campaign.api.dto.CampaignRunResponse
import com.project.agenticreliabilitylab.campaign.api.dto.CreateCampaignRequest
import com.project.agenticreliabilitylab.campaign.application.CampaignExecutionService
import com.project.agenticreliabilitylab.campaign.application.model.StartStockConcurrencyCampaign
import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyParametersInput
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
@RequestMapping("/api/campaigns")
class CampaignController(
    private val service: CampaignExecutionService,
) {
    @PostMapping
    fun start(
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateCampaignRequest,
    ): ResponseEntity<CampaignRunResponse> {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        require(!request.targetSystem.isNullOrBlank()) { "targetSystem is required" }
        val parameters = request.parameters?.toDomain() ?: throw IllegalArgumentException("parameters is required")
        val repeatCount = request.repeatCount ?: throw IllegalArgumentException("repeatCount is required")
        val campaign = service.start(
            StartStockConcurrencyCampaign(request.targetSystem, parameters, repeatCount),
            idempotencyKey,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(CampaignRunResponse.from(campaign))
    }

    @GetMapping("/{id}")
    fun find(@PathVariable id: UUID): CampaignRunDetailResponse =
        CampaignRunDetailResponse.from(service.find(id), service.steps(id))

}
