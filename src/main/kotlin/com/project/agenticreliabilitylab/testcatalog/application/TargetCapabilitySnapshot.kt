package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import java.util.UUID

/**
 * What the currently active Profile actually allows ARL to run against one Target.
 *
 * This is read fresh whenever candidates are generated or listed. It is never persisted alongside a candidate, so
 * enabling execution or registering an operation changes candidate readiness without rewriting stored rows.
 */
data class TargetCapabilitySnapshot(
    val targetSystemId: String,
    val profileVersionId: UUID,
    val genericExecutionEnabled: Boolean,
    val readOnlyCandidatePathsById: Map<String, String>,
    val availableExperimentTypes: Set<ExperimentType>,
) {
    val registeredReadOnlyCandidateIds: Set<String> = readOnlyCandidatePathsById.keys

    fun candidateIdForPath(path: String): String? = readOnlyCandidatePathsById.entries
        .firstOrNull { (_, registeredPath) -> registeredPath == path }
        ?.key
}
