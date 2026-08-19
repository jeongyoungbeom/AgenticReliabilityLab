package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedInvariant
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence

data class ExtractedInvariantResponse(
    val statement: String,
    val confidence: String,
    val confirmationRequired: Boolean,
    val citations: List<KnowledgeCitationResponse>,
) {
    companion object {
        fun from(invariant: ExtractedInvariant): ExtractedInvariantResponse = ExtractedInvariantResponse(
            statement = invariant.statement,
            confidence = invariant.confidence.name,
            confirmationRequired = invariant.confidence == KnowledgeConfidence.ASSUMPTION,
            citations = invariant.citations.map(KnowledgeCitationResponse::from),
        )
    }
}
