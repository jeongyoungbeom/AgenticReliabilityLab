package com.project.agenticreliabilitylab.campaign.application.model

import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters

data class StartStockConcurrencyCampaign(
    val targetSystemId: String,
    val parameters: StockConcurrencyParameters,
    val repeatCount: Int,
)
