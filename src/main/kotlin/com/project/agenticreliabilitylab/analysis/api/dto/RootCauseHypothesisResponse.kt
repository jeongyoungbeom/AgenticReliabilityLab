package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.HypothesisConfidence

data class RootCauseHypothesisResponse(
    val ordinal: Int,
    val title: String,
    val confidence: HypothesisConfidence,
    val rationale: String,
    val falsifiability: String,
    val evidenceIds: List<String>,
)
