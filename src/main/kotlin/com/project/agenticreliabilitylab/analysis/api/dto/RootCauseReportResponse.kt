package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportDetails
import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportStatus
import java.time.Instant

data class RootCauseReportResponse(
    val id: String,
    val analysisRunId: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputChecksum: String,
    val outputChecksum: String?,
    val status: RootCauseReportStatus,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val implementationAvailable: Boolean,
    val hypotheses: List<RootCauseHypothesisResponse>,
    val improvementProposals: List<ImprovementProposalResponse>,
) {
    companion object {
        fun from(details: RootCauseReportDetails) = RootCauseReportResponse(
            id = details.run.id.toString(),
            analysisRunId = details.run.analysisRunId.toString(),
            modelKey = details.run.modelKey,
            modelId = details.run.modelId,
            promptVersion = details.run.promptVersion,
            inputChecksum = details.run.inputChecksum,
            outputChecksum = details.run.outputChecksum,
            status = details.run.status,
            promptTokenCount = details.run.promptTokenCount,
            completionTokenCount = details.run.completionTokenCount,
            durationMillis = details.run.durationMillis,
            failureCode = details.run.failureCode,
            failureMessage = details.run.failureMessage,
            requestedAt = details.run.requestedAt,
            startedAt = details.run.startedAt,
            completedAt = details.run.completedAt,
            implementationAvailable = false,
            hypotheses = details.hypotheses.map {
                RootCauseHypothesisResponse(
                    it.ordinal,
                    it.title,
                    it.confidence,
                    it.rationale,
                    it.falsifiability,
                    it.evidenceIds,
                )
            },
            improvementProposals = details.improvementProposals.map {
                ImprovementProposalResponse(
                    it.ordinal,
                    it.hypothesisOrdinal,
                    it.title,
                    it.proposedChange,
                    it.expectedEffect,
                    it.risk,
                    it.evidenceIds,
                )
            },
        )
    }
}
