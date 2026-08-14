package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.application.model.RequestedComparisonConfiguration
import com.project.agenticreliabilitylab.analysis.domain.AnalysisArchitecture

data class CreateAnalysisComparisonRequest(
    val modelKeys: List<String>? = null,
    val configurations: List<CreateAnalysisComparisonConfigurationRequest>? = null,
)

fun CreateAnalysisComparisonRequest?.toRequestedConfigurations(): List<RequestedComparisonConfiguration>? {
    if (this == null) return null
    require(modelKeys == null || configurations == null) {
        "modelKeys and configurations cannot be used together"
    }
    return configurations?.map { RequestedComparisonConfiguration(it.architecture, it.modelKey) }
        ?: modelKeys?.map { RequestedComparisonConfiguration(AnalysisArchitecture.SINGLE, it) }
}
