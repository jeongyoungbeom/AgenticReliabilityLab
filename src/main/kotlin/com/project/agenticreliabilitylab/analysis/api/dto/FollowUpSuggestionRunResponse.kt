package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.FollowUpSuggestionRunDetails
import com.project.agenticreliabilitylab.analysis.domain.FollowUpSuggestionRunStatus
import java.time.Instant

data class FollowUpSuggestionRunResponse(
    val id: String,
    val analysisRunId: String,
    val targetTestBatchId: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputChecksum: String,
    val status: FollowUpSuggestionRunStatus,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val suggestions: List<FollowUpTestSuggestionResponse>,
) {
    companion object {
        fun from(details: FollowUpSuggestionRunDetails) = FollowUpSuggestionRunResponse(
            id = details.run.id.toString(),
            analysisRunId = details.run.analysisRunId.toString(),
            targetTestBatchId = details.run.targetTestBatchId.toString(),
            modelKey = details.run.modelKey,
            modelId = details.run.modelId,
            promptVersion = details.run.promptVersion,
            inputChecksum = details.run.inputChecksum,
            status = details.run.status,
            promptTokenCount = details.run.promptTokenCount,
            completionTokenCount = details.run.completionTokenCount,
            durationMillis = details.run.durationMillis,
            failureCode = details.run.failureCode,
            failureMessage = details.run.failureMessage,
            requestedAt = details.run.requestedAt,
            startedAt = details.run.startedAt,
            completedAt = details.run.completedAt,
            suggestions = details.suggestions.map { suggestion ->
                FollowUpTestSuggestionResponse(
                    suggestion.ordinal,
                    suggestion.candidateId,
                    suggestion.candidateTitle,
                    suggestion.rationale,
                    suggestion.evidenceIds,
                )
            },
        )
    }
}
