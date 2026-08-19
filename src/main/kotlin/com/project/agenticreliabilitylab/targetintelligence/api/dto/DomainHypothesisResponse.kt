package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.DomainHypothesis
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence

data class DomainHypothesisResponse(
    val concept: String,
    val description: String,
    val confidence: String,
    val confirmationRequired: Boolean,
    val citations: List<KnowledgeCitationResponse>,
) {
    companion object {
        fun from(hypothesis: DomainHypothesis): DomainHypothesisResponse = DomainHypothesisResponse(
            concept = hypothesis.concept,
            description = hypothesis.description,
            confidence = hypothesis.confidence.name,
            confirmationRequired = hypothesis.confidence == KnowledgeConfidence.ASSUMPTION,
            citations = hypothesis.citations.map(KnowledgeCitationResponse::from),
        )
    }
}
