package com.project.agenticreliabilitylab.targetspec.application.port

import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import java.time.Duration

data class TargetTestExecutionSettings(
    val hostResourceGroup: String,
    val requestTimeout: Duration,
)

/** Application-facing catalog of profile-defined, safe target test capabilities. */
interface TargetTestCatalog {
    fun candidates(targetSystemId: String, healthPath: String): List<TargetTestCandidate>
    fun maxBatchSize(targetSystemId: String): Int
    fun requireGenericExecutionEnabled(targetSystemId: String): TargetTestExecutionSettings
    fun failureInjectionCandidates(targetSystemId: String): List<FailureInjectionCandidate>
    fun requireFailureInjectionPlanningEnabled(targetSystemId: String)
}
