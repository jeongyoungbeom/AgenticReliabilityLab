package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.analysis.domain.HypothesisConfidence
import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportDetails
import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportRunRecord
import java.time.Instant
import java.util.UUID

/** Persistence boundary for evidence-grounded root-cause reports. */
interface RootCauseReportStore {
    fun create(command: NewRootCauseReportRun)
    fun findById(id: UUID): RootCauseReportRunRecord?
    fun findByAnalysisAndIdempotencyKey(analysisRunId: UUID, idempotencyKey: String): RootCauseReportRunRecord?
    fun findDetails(id: UUID): RootCauseReportDetails?
    fun claim(id: UUID, now: Instant): Boolean
    fun complete(id: UUID, completion: RootCauseReportCompletion, now: Instant)
    fun fail(id: UUID, code: String, message: String, now: Instant)
    fun findRequestedIds(): List<UUID>
    fun findRunningIds(): List<UUID>
}

data class NewRootCauseReportRun(
    val id: UUID,
    val analysisRunId: UUID,
    val idempotencyKey: String,
    val configurationHash: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputBundleJson: String,
    val inputChecksum: String,
    val requestedAt: Instant,
)

data class NewRootCauseHypothesis(
    val id: UUID,
    val title: String,
    val confidence: HypothesisConfidence,
    val rationale: String,
    val falsifiability: String,
    val evidenceIds: List<String>,
)

data class NewImprovementProposal(
    val id: UUID,
    val hypothesisOrdinal: Int,
    val title: String,
    val proposedChange: String,
    val expectedEffect: String,
    val risk: String,
    val evidenceIds: List<String>,
)

data class RootCauseReportCompletion(
    val outputJson: String,
    val outputChecksum: String?,
    val hypotheses: List<NewRootCauseHypothesis>,
    val improvementProposals: List<NewImprovementProposal>,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
)
