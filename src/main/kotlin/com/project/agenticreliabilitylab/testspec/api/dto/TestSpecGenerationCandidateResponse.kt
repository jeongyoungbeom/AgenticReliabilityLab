package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationCandidateOutcome
import tools.jackson.databind.JsonNode

/**
 * [document] is included for a REJECTED candidate too, since a rejected proposal is never stored as a
 * TestSpecification and this response is the only place a reviewer can see what the model actually tried.
 */
data class TestSpecGenerationCandidateResponse(
    val ordinal: Int,
    val outcome: TestSpecGenerationCandidateOutcome,
    val specKey: String,
    val title: String,
    val document: JsonNode,
    val rejectionReason: String?,
    val specificationId: String?,
)
