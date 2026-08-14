package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunDetails
import java.util.UUID

/**
 * A deliberately narrow model port. Phase 2 gives the model a fixed evidence
 * bundle and does not expose target, shell, database, or side-effect tools.
 */
interface ReliabilityAnalysisModel {
    fun analyze(request: ReliabilityAnalysisModelRequest): ReliabilityAnalysisModelResponse
}

/** Common read-only result contract shared by single and multi-agent architectures. */
interface ReliabilityAnalysisAgent {
    val agentType: String

    fun find(analysisRunId: UUID): AnalysisRunDetails
}

data class ReliabilityAnalysisModelRequest(
    val modelId: String,
    val systemInstruction: String,
    val evidenceBundleJson: String,
    val evidenceIds: List<String>,
)

data class ReliabilityAnalysisModelResponse(
    val content: String,
    val promptTokenCount: Int? = null,
    val completionTokenCount: Int? = null,
    val durationMillis: Long? = null,
)

class AnalysisModelUnavailableException(message: String) : RuntimeException(message)
