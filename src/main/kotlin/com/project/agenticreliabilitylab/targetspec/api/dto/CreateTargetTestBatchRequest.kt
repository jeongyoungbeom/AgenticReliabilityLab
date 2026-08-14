package com.project.agenticreliabilitylab.targetspec.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

data class CreateTargetTestBatchRequest(
    @field:NotBlank val targetSystemId: String,
    @field:NotEmpty val candidateIds: List<@NotBlank String>,
)
