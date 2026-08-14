package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.analysis.domain.FollowUpSuggestionRunDetails
import com.project.agenticreliabilitylab.analysis.domain.FollowUpSuggestionRunRecord
import java.time.Instant
import java.util.UUID

/** Persistence boundary for advisory follow-up test suggestions. */
interface FollowUpSuggestionStore {
    fun create(command: NewFollowUpSuggestionRun)
    fun findById(id: UUID): FollowUpSuggestionRunRecord?
    fun findByAnalysisAndIdempotencyKey(analysisRunId: UUID, idempotencyKey: String): FollowUpSuggestionRunRecord?
    fun findDetails(id: UUID): FollowUpSuggestionRunDetails?
    fun claim(id: UUID, now: Instant): Boolean
    fun complete(id: UUID, completion: FollowUpSuggestionCompletion, now: Instant)
    fun fail(id: UUID, code: String, message: String, now: Instant)
    fun findRequestedIds(): List<UUID>
    fun findRunningIds(): List<UUID>
}

data class NewFollowUpSuggestionRun(
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
    val requestedAt: Instant,
)

data class NewFollowUpTestSuggestion(
    val id: UUID,
    val candidateId: String,
    val candidateTitle: String,
    val rationale: String,
    val evidenceIds: List<String>,
)

data class FollowUpSuggestionCompletion(
    val outputJson: String,
    val suggestions: List<NewFollowUpTestSuggestion>,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
)
