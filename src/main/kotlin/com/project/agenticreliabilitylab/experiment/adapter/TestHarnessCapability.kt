package com.project.agenticreliabilitylab.experiment.adapter

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType

/**
 * One capability a Target Test Harness declares.
 *
 * A declaration never widens what ARL may run. The Target Profile and the Experiment Catalog remain the ceiling, so a
 * capability can only describe an equal or narrower bound; anything larger is clamped back to the Profile.
 */
data class TestHarnessCapability(
    val id: String,
    val version: String,
    val experimentType: ExperimentType,
    val environments: Set<String>,
    val testNamespace: String,
    val maxStock: Int,
    val maxRequestCount: Int,
    val maxConcurrency: Int,
    val maxQuantityPerRequest: Int,
    val invariantIds: List<String>,
    val cleanupVerification: String,
) {
    val cleanupVerificationRequired: Boolean = cleanupVerification == CLEANUP_REQUIRED

    companion object {
        const val CONTRACT_VERSION = "TEST_HARNESS_V1"
        const val CLEANUP_REQUIRED = "REQUIRED"
    }
}
