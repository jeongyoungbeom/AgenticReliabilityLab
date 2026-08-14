package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisArchitecture
import jakarta.validation.constraints.NotBlank

data class CreateAnalysisComparisonConfigurationRequest(
    val architecture: AnalysisArchitecture,
    @field:NotBlank val modelKey: String,
)
