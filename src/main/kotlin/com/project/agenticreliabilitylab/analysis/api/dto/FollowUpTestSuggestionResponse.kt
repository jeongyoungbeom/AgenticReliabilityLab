package com.project.agenticreliabilitylab.analysis.api.dto

data class FollowUpTestSuggestionResponse(
    val ordinal: Int,
    val candidateId: String,
    val candidateTitle: String,
    val rationale: String,
    val evidenceIds: List<String>,
)
