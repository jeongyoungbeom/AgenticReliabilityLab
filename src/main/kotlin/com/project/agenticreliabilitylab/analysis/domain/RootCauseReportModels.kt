package com.project.agenticreliabilitylab.analysis.domain

import java.time.Instant
import java.util.UUID

/**
 * A Phase 9 report is advice only: no child record is a command, approval, or
 * Target change. The input bundle snapshots the completed analysis and evidence.
 */
data class RootCauseReportRunRecord(
    val id: UUID,
    val analysisRunId: UUID,
    val idempotencyKey: String,
    val configurationHash: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputBundleJson: String,
    val inputChecksum: String,
    val outputChecksum: String?,
    val status: RootCauseReportStatus,
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

data class RootCauseHypothesisRecord(
    val id: UUID,
    val reportRunId: UUID,
    val ordinal: Int,
    val title: String,
    val confidence: HypothesisConfidence,
    val rationale: String,
    val falsifiability: String,
    val evidenceIds: List<String>,
)

data class ImprovementProposalRecord(
    val id: UUID,
    val reportRunId: UUID,
    val ordinal: Int,
    val hypothesisOrdinal: Int,
    val title: String,
    val proposedChange: String,
    val expectedEffect: String,
    val risk: String,
    val evidenceIds: List<String>,
)

data class RootCauseReportDetails(
    val run: RootCauseReportRunRecord,
    val hypotheses: List<RootCauseHypothesisRecord>,
    val improvementProposals: List<ImprovementProposalRecord>,
)
