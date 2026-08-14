package com.project.agenticreliabilitylab.analysis.domain

import java.time.Instant
import java.util.UUID

/** A read-only recommendation to select an existing safe Target test candidate. */
data class FollowUpSuggestionRunRecord(
    val id: UUID,
    val analysisRunId: UUID,
    val targetTestBatchId: UUID,
    val idempotencyKey: String,
    val configurationHash: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputBundleJson: String,
    val inputChecksum: String,
    val status: FollowUpSuggestionRunStatus,
    val outputJson: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class FollowUpTestSuggestionRecord(
    val id: UUID,
    val suggestionRunId: UUID,
    val ordinal: Int,
    val candidateId: String,
    val candidateTitle: String,
    val rationale: String,
    val evidenceIds: List<String>,
)

data class FollowUpSuggestionRunDetails(
    val run: FollowUpSuggestionRunRecord,
    val suggestions: List<FollowUpTestSuggestionRecord>,
)
