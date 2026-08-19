package com.project.agenticreliabilitylab.testplan.api.dto

import jakarta.validation.constraints.NotBlank

data class ApproveTestPlanRequest(
    @field:NotBlank val confirmation: String,
)
