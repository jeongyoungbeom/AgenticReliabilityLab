package com.project.agenticreliabilitylab.analysis.application.port

data class AnalysisModelDefinition(
    val key: String,
    val modelId: String,
)

interface AnalysisModelCatalog {
    fun resolve(requestedKey: String?, defaultKey: String): AnalysisModelDefinition
    fun resolveRequired(key: String): AnalysisModelDefinition
}

interface ReliabilityAgentSettings {
    val enabled: Boolean
    val defaultModelKey: String
    val promptVersion: String
    val maxEvidenceCount: Int
    val maxEvidenceBytes: Int
    val maxOutputBytes: Int
}

interface MultiAgentSettings {
    val enabled: Boolean
    val promptVersion: String
    val maxStepOutputBytes: Int
}

interface FollowUpSuggestionSettings {
    val enabled: Boolean
    val promptVersion: String
    val maxOutputBytes: Int
    val maxSuggestions: Int
    val maxCandidateCatalogCount: Int
    val maxCandidateCatalogBytes: Int
    val maxInputBytes: Int
}

interface RootCauseReportSettings {
    val enabled: Boolean
    val promptVersion: String
    val maxOutputBytes: Int
    val maxHypotheses: Int
    val maxImprovementProposals: Int
    val maxInputBytes: Int
}
