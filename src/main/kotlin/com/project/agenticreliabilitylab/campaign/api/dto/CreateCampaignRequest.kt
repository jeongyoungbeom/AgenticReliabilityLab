package com.project.agenticreliabilitylab.campaign.api.dto

import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyParametersInput
import jakarta.validation.Valid

data class CreateCampaignRequest(
    val targetSystem: String?,
    @field:Valid val parameters: StockConcurrencyParametersInput?,
    val repeatCount: Int?,
)
