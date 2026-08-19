package com.project.agenticreliabilitylab.testplan.api.dto

import com.project.agenticreliabilitylab.testplan.application.CreateTestPlan
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CreateTestPlanRequest(
    @field:NotNull val generationId: UUID?,
    @field:NotEmpty val candidateIds: List<UUID>,
) {
    fun toCommand(): CreateTestPlan = CreateTestPlan(
        generationId = requireNotNull(generationId) { "generationId is required" },
        candidateIds = candidateIds,
    )
}
