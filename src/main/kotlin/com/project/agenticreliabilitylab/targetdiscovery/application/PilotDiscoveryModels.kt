package com.project.agenticreliabilitylab.targetdiscovery.application

enum class PilotCandidateReadiness {
    READY,
    NOT_READY,
}

data class PilotDiscoveredOperation(
    val method: String,
    val swaggerPath: String,
    val executionPath: String,
    val operationId: String?,
    val authProfile: String?,
    val summary: String?,
)

data class PilotTestCandidate(
    val id: String,
    val title: String,
    val description: String,
    val readiness: PilotCandidateReadiness,
    val operations: List<PilotDiscoveredOperation>,
    val missingOperations: List<String>,
)

data class PilotDiscovery(
    val targetSystemId: String,
    val profileVersionId: String,
    val openApiPath: String,
    val openApiPaths: List<String>,
    val snapshotId: String,
    val snapshotChecksum: String,
    val snapshotChecksums: List<String>,
    val discoveredOperations: List<PilotDiscoveredOperation>,
    val ignoredOperationCount: Int,
    val candidates: List<PilotTestCandidate>,
)
