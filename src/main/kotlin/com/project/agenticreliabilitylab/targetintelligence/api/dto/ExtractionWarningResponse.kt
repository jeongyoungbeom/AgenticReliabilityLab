package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractionWarning

data class ExtractionWarningResponse(
    val code: String,
    val message: String,
) {
    companion object {
        fun from(warning: ExtractionWarning): ExtractionWarningResponse = ExtractionWarningResponse(
            code = warning.code,
            message = warning.message,
        )
    }
}
