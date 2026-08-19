package com.project.agenticreliabilitylab.targetintelligence.api.dto

import jakarta.validation.constraints.NotBlank

data class ConfirmTargetKnowledgeSnapshotRequest(
    @field:NotBlank val confirmation: String,
)
