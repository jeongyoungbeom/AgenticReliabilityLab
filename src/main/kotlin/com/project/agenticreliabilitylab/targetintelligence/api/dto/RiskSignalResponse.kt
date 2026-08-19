package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignal

data class RiskSignalResponse(
    val type: String,
    val confidence: String,
    val citation: KnowledgeCitationResponse,
) {
    companion object {
        fun from(signal: RiskSignal): RiskSignalResponse = RiskSignalResponse(
            type = signal.type.name,
            confidence = signal.confidence.name,
            citation = KnowledgeCitationResponse.from(signal.citation),
        )
    }
}
