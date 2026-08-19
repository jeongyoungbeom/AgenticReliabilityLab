package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeCitation

data class KnowledgeCitationResponse(
    val sourceType: String,
    val location: String,
    val excerpt: String,
) {
    companion object {
        fun from(citation: KnowledgeCitation): KnowledgeCitationResponse = KnowledgeCitationResponse(
            sourceType = citation.sourceType.name,
            location = citation.location,
            excerpt = citation.excerpt,
        )
    }
}
