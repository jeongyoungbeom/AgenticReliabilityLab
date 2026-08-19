package com.project.agenticreliabilitylab.testplan.application

import java.util.UUID

/** Selection of already-generated candidates. Nothing here can name an endpoint or an execution unit directly. */
data class CreateTestPlan(
    val generationId: UUID,
    val candidateIds: List<UUID>,
)
