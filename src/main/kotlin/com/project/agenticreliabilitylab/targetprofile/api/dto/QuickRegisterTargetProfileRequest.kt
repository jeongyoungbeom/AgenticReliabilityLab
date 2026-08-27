package com.project.agenticreliabilitylab.targetprofile.api.dto

import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** The deliberately small registration surface for the standard Pilot contract. */
data class QuickRegisterTargetProfileRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    @field:NotBlank
    @field:Size(max = 2_000)
    val baseUrl: String,
    val environment: TargetEnvironment,
)
