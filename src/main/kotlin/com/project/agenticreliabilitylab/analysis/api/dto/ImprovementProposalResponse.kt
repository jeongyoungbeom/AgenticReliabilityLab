package com.project.agenticreliabilitylab.analysis.api.dto

data class ImprovementProposalResponse(
    val ordinal: Int,
    val hypothesisOrdinal: Int,
    val title: String,
    val proposedChange: String,
    val expectedEffect: String,
    val risk: String,
    val evidenceIds: List<String>,
)
