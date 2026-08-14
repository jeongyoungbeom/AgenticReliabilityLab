package com.project.agenticreliabilitylab.targetprofiledraft.api.dto

import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraft

data class TargetProfileDraftResponse(
    val source: String,
    val suggestedTargetId: String,
    val suggestedTargetName: String,
    val suggestedBaseUrl: String?,
    val readOnlyOperations: List<DraftReadOnlyOperationResponse>,
    val yaml: String,
    val warnings: List<String>,
) {
    companion object {
        fun from(draft: TargetProfileDraft): TargetProfileDraftResponse = TargetProfileDraftResponse(
            source = draft.source.name,
            suggestedTargetId = draft.suggestedTargetId,
            suggestedTargetName = draft.suggestedTargetName,
            suggestedBaseUrl = draft.suggestedBaseUrl,
            readOnlyOperations = draft.readOnlyOperations.map(DraftReadOnlyOperationResponse::from),
            yaml = draft.yaml,
            warnings = draft.warnings,
        )
    }
}
