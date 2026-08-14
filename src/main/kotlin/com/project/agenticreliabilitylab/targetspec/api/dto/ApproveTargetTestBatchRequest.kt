package com.project.agenticreliabilitylab.targetspec.api.dto

import jakarta.validation.constraints.NotBlank

data class ApproveTargetTestBatchRequest(@field:NotBlank val confirmation: String)
