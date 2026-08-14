package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.application.model.AnalysisComparisonConfiguration
import com.project.agenticreliabilitylab.analysis.domain.AnalysisArchitecture

data class AnalysisComparisonConfigurationResponse(
    val selectionKey: String,
    val architecture: AnalysisArchitecture,
    val modelKey: String,
) {
    companion object {
        fun from(configuration: AnalysisComparisonConfiguration) = AnalysisComparisonConfigurationResponse(
            selectionKey = configuration.selectionKey,
            architecture = configuration.architecture,
            modelKey = configuration.modelKey,
        )
    }
}
