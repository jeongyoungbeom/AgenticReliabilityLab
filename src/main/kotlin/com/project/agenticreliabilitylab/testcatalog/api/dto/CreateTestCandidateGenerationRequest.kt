package com.project.agenticreliabilitylab.testcatalog.api.dto

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CreateTestCandidateGenerationRequest(
    @field:NotNull val knowledgeSnapshotId: UUID?,
)
