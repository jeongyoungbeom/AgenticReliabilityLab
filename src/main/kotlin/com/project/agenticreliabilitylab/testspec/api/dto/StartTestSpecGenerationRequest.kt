package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.application.StartTestSpecGeneration
import jakarta.validation.constraints.Size
import java.util.UUID

/** Accepts multiple confirmed Snapshot IDs while keeping the old singular field for existing clients. */
data class StartTestSpecGenerationRequest(
    @field:Size(max = MAX_KNOWLEDGE_SNAPSHOT_ID_CHARACTERS)
    val knowledgeSnapshotId: String? = null,
    @field:Size(max = MAX_SNAPSHOTS)
    val knowledgeSnapshotIds: List<String>? = null,
    @field:Size(max = MAX_OPENAPI_DOCUMENT_CHARACTERS)
    val openApiDocument: String? = null,
    val modelKey: String? = null,
) {
    fun toCommand(
        targetSystemId: String,
        credentialSessionId: String?,
    ): StartTestSpecGeneration = StartTestSpecGeneration(
        targetSystemId = targetSystemId,
        knowledgeSnapshotIds = (knowledgeSnapshotIds ?: listOfNotNull(knowledgeSnapshotId)).map(UUID::fromString),
        openApiDocument = openApiDocument,
        requestedModelKey = modelKey,
        credentialSessionId = credentialSessionId,
    )

    private companion object {
        const val MAX_KNOWLEDGE_SNAPSHOT_ID_CHARACTERS = 36
        const val MAX_OPENAPI_DOCUMENT_CHARACTERS = 1_048_576
        const val MAX_SNAPSHOTS = 10
    }
}
