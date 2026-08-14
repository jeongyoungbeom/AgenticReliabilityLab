package com.project.agenticreliabilitylab.targetprofiledraft.domain

enum class TargetProfileDraftSource {
    OPENAPI,
    README,
}

data class TargetProfileDraft(
    val source: TargetProfileDraftSource,
    val suggestedTargetId: String,
    val suggestedTargetName: String,
    val suggestedBaseUrl: String?,
    val readOnlyOperations: List<DraftReadOnlyOperation>,
    val yaml: String,
    val warnings: List<String>,
)

data class DraftReadOnlyOperation(
    val id: String,
    val title: String,
    val description: String,
    val path: String,
    val expectedStatusCodes: Set<Int>,
)

class TargetProfileDraftException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
