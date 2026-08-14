package com.project.agenticreliabilitylab.targetprofile.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ImportTargetProfileRequest(
    @field:NotBlank
    @field:Size(max = MAX_PROFILE_DOCUMENT_CHARACTERS)
    val yaml: String,
) {
    private companion object {
        const val MAX_PROFILE_DOCUMENT_CHARACTERS = 65_536
    }
}
