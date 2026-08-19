package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.application.CreateTargetKnowledgeSnapshot
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Supplied documents only. No URL, repository path or Target address is accepted. */
data class CreateTargetKnowledgeSnapshotRequest(
    @field:NotBlank
    val targetSystemId: String,
    @field:Size(max = MAX_OPENAPI_DOCUMENT_CHARACTERS)
    val openApiDocument: String? = null,
    @field:Size(max = MAX_README_DOCUMENT_CHARACTERS)
    val readmeDocument: String? = null,
    @field:Valid
    val brief: TargetBriefRequest? = null,
) {
    fun toCommand(): CreateTargetKnowledgeSnapshot = CreateTargetKnowledgeSnapshot(
        targetSystemId = targetSystemId,
        openApiDocument = openApiDocument,
        readmeDocument = readmeDocument,
        brief = brief?.toInput(),
    )

    private companion object {
        const val MAX_OPENAPI_DOCUMENT_CHARACTERS = 1_048_576
        const val MAX_README_DOCUMENT_CHARACTERS = 262_144
    }
}
