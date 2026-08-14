package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisRecommendationRecord
import com.project.agenticreliabilitylab.analysis.domain.RecommendationPriority

data class AnalysisRecommendationResponse(
    val ordinal: Int,
    val priority: RecommendationPriority,
    val title: String,
    val recommendedAction: String,
    val rationale: String,
    val evidenceIds: List<String>,
) {
    companion object {
        fun from(recommendation: AnalysisRecommendationRecord) = AnalysisRecommendationResponse(
            ordinal = recommendation.ordinal,
            priority = recommendation.priority,
            title = recommendation.title,
            recommendedAction = recommendation.recommendedAction,
            rationale = recommendation.rationale,
            evidenceIds = recommendation.evidenceIds,
        )
    }
}
