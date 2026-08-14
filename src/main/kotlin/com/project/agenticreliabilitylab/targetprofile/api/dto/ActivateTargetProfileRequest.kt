package com.project.agenticreliabilitylab.targetprofile.api.dto

import jakarta.validation.constraints.NotBlank

data class ActivateTargetProfileRequest(@field:NotBlank val confirmation: String)
