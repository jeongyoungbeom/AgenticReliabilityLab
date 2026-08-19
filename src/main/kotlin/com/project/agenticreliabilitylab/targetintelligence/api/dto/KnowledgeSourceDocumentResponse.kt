package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceDocument

data class KnowledgeSourceDocumentResponse(
    val type: String,
    val byteCount: Int,
    val checksum: String,
) {
    companion object {
        fun from(source: KnowledgeSourceDocument): KnowledgeSourceDocumentResponse = KnowledgeSourceDocumentResponse(
            type = source.type.name,
            byteCount = source.byteCount,
            checksum = source.checksum,
        )
    }
}
