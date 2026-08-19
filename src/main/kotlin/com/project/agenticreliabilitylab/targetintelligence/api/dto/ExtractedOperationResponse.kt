package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedOperation

data class ExtractedOperationResponse(
    val method: String,
    val path: String,
    val operationId: String?,
    val summary: String?,
    val requestMediaTypes: Set<String>,
    val responseStatusCodes: Set<Int>,
    val mutability: String,
    val citation: KnowledgeCitationResponse,
) {
    companion object {
        fun from(operation: ExtractedOperation): ExtractedOperationResponse = ExtractedOperationResponse(
            method = operation.method,
            path = operation.path,
            operationId = operation.operationId,
            summary = operation.summary,
            requestMediaTypes = operation.requestMediaTypes,
            responseStatusCodes = operation.responseStatusCodes,
            mutability = operation.mutability.name,
            citation = KnowledgeCitationResponse.from(operation.citation),
        )
    }
}
