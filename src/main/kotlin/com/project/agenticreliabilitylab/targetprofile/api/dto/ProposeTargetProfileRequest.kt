package com.project.agenticreliabilitylab.targetprofile.api.dto

import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ProposeTargetProfileRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank @field:Size(max = 2_000) val baseUrl: String,
    val environment: TargetEnvironment,
    @field:Size(min = 1, max = 8) val openApiPaths: List<String>,
    val manifestPath: String? = null,
    @field:NotBlank val harnessKey: String,
)
