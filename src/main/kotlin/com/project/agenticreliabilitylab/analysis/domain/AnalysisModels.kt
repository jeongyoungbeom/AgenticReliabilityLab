package com.project.agenticreliabilitylab.analysis.domain

import java.time.Instant
import java.util.UUID

enum class AnalysisRunStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
}

/** The isolated reasoning architecture used for one comparison selection. */
enum class AnalysisArchitecture {
    SINGLE,
    MULTI,
}

enum class FollowUpSuggestionRunStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
}

/** Lifecycle of a persisted, read-only Phase 9 diagnostic report. */
enum class RootCauseReportStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class HypothesisConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

enum class MultiAgentRole {
    SUPERVISOR,
    PLANNER,
    ANALYST,
    REVIEWER,
}

enum class AgentStepRunStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class LlmInvocationStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class FindingSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class RecommendationPriority {
    P0,
    P1,
    P2,
    P3,
}

/** The deterministic outcome which an analysis says the evidence supports. */
enum class AnalysisVerdict {
    PASSED,
    FAILED,
    INCONCLUSIVE,
}

data class AnalysisRunRecord(
    val id: UUID,
    val experimentRunId: UUID?,
    val targetTestBatchId: UUID?,
    val idempotencyKey: String,
    val agentType: String,
    val agentVersion: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val analysisDatasetId: UUID?,
    val inputChecksum: String?,
    val inputEvidenceCount: Int?,
    val status: AnalysisRunStatus,
    val verdict: AnalysisVerdict?,
    val summary: String?,
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

data class AnalysisFindingRecord(
    val id: UUID,
    val analysisRunId: UUID,
    val ordinal: Int,
    val severity: FindingSeverity,
    val title: String,
    val rationale: String,
    val evidenceIds: List<String>,
)

data class AnalysisRecommendationRecord(
    val id: UUID,
    val analysisRunId: UUID,
    val ordinal: Int,
    val priority: RecommendationPriority,
    val title: String,
    val recommendedAction: String,
    val rationale: String,
    val evidenceIds: List<String>,
)

data class AnalysisRunDetails(
    val run: AnalysisRunRecord,
    val findings: List<AnalysisFindingRecord>,
    val recommendations: List<AnalysisRecommendationRecord>,
)

data class AnalysisDatasetRecord(
    val id: UUID,
    val experimentRunId: UUID?,
    val targetTestBatchId: UUID?,
    val testSpecRunId: UUID? = null,
    val contractVersion: String,
    val evidenceBundleJson: String,
    val evidenceIds: List<String>,
    val checksum: String,
    val evidenceCount: Int,
    val createdAt: Instant,
)

data class AnalysisGroundTruthRecord(
    val id: UUID,
    val analysisDatasetId: UUID,
    val version: String,
    val expectedVerdict: AnalysisVerdict,
    val requiredEvidenceIds: List<String>,
    val notes: String?,
    val createdAt: Instant,
)

data class AnalysisEvaluationRecord(
    val id: UUID,
    val analysisRunId: UUID,
    val analysisGroundTruthId: UUID,
    val evaluationVersion: String,
    val verdictMatch: Boolean,
    val citedRequiredEvidenceCount: Int,
    val requiredEvidenceCount: Int,
    val citationRecall: Double,
    val score: Double,
    val evaluatedAt: Instant,
)

data class AnalysisComparisonRecord(
    val id: UUID,
    val experimentRunId: UUID?,
    val targetTestBatchId: UUID?,
    val analysisDatasetId: UUID,
    val idempotencyKey: String,
    val modelKeys: List<String>,
    val configurationJson: String?,
    val configurationHash: String?,
    val requestedAt: Instant,
)

data class AnalysisComparisonRunRecord(
    val comparisonId: UUID,
    val modelKey: String,
    val analysisRunId: UUID,
)

data class AgentStepRunRecord(
    val id: UUID,
    val analysisRunId: UUID,
    val sequenceNumber: Int,
    val role: MultiAgentRole,
    val status: AgentStepRunStatus,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val toolPolicy: String,
    val inputChecksum: String?,
    val inputContextJson: String?,
    val outputJson: String?,
    val outputChecksum: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class LlmInvocationRecord(
    val id: UUID,
    val agentStepRunId: UUID,
    val ordinal: Int,
    val status: LlmInvocationStatus,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val toolCallCount: Int,
    val inputChecksum: String,
    val outputChecksum: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
)
