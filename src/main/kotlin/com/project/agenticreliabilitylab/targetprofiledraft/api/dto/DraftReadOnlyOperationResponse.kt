package com.project.agenticreliabilitylab.targetprofiledraft.api.dto

import com.project.agenticreliabilitylab.targetprofiledraft.domain.DraftReadOnlyOperation

data class DraftReadOnlyOperationResponse(
    val id: String,
    val title: String,
    val description: String,
    val path: String,
    val expectedStatusCodes: Set<Int>,
) {
    companion object {
        fun from(operation: DraftReadOnlyOperation): DraftReadOnlyOperationResponse = DraftReadOnlyOperationResponse(
            id = operation.id,
            title = operation.title,
            description = operation.description,
            path = operation.path,
            expectedStatusCodes = operation.expectedStatusCodes,
        )
    }
}
