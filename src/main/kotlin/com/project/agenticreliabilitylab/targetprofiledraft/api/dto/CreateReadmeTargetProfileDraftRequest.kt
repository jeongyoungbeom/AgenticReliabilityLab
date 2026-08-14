package com.project.agenticreliabilitylab.targetprofiledraft.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateReadmeTargetProfileDraftRequest(
    @field:NotBlank
    @field:Size(max = MAX_README_DOCUMENT_CHARACTERS)
    val document: String,
) {
    private companion object {
        const val MAX_README_DOCUMENT_CHARACTERS = 262_144
    }
}
