package com.project.agenticreliabilitylab.targetprofiledraft.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateTargetProfileDraftRequest(
    @field:NotBlank
    @field:Size(max = MAX_OPENAPI_DOCUMENT_CHARACTERS)
    val document: String,
) {
    private companion object {
        const val MAX_OPENAPI_DOCUMENT_CHARACTERS = 1_048_576
    }
}
