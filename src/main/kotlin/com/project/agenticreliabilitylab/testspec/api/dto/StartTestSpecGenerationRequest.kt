package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.application.StartTestSpecGeneration
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/** Accepts a raw OpenAPI document fresh on every call; see [StartTestSpecGeneration] for why it is not persisted. */
data class StartTestSpecGenerationRequest(
    @field:NotBlank
    @field:Size(max = MAX_KNOWLEDGE_SNAPSHOT_ID_CHARACTERS)
    val knowledgeSnapshotId: String,
    @field:Size(max = MAX_OPENAPI_DOCUMENT_CHARACTERS)
    val openApiDocument: String? = null,
    val modelKey: String? = null,
) {
    fun toCommand(targetSystemId: String): StartTestSpecGeneration = StartTestSpecGeneration(
        targetSystemId = targetSystemId,
        knowledgeSnapshotId = UUID.fromString(knowledgeSnapshotId),
        openApiDocument = openApiDocument,
        requestedModelKey = modelKey,
    )

    private companion object {
        const val MAX_KNOWLEDGE_SNAPSHOT_ID_CHARACTERS = 36
        const val MAX_OPENAPI_DOCUMENT_CHARACTERS = 1_048_576
    }
}
