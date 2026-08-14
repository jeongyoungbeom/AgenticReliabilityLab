package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

data class CreateGroundTruthRequest(
    @field:NotBlank val version: String,
    val expectedVerdict: AnalysisVerdict,
    @field:NotEmpty val requiredEvidenceIds: List<@NotBlank String>,
    val notes: String? = null,
)
