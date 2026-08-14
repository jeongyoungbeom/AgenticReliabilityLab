package com.project.agenticreliabilitylab.experiment.api.dto

import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyParametersInput
import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import jakarta.validation.Valid

data class CreateExperimentRequest(
    val targetSystem: String?,
    val type: ExperimentType?,
    @field:Valid val parameters: StockConcurrencyParametersInput?,
)
