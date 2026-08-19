package com.project.agenticreliabilitylab.experiment.domain

import java.time.Duration

/**
 * Immutable, validated capability configuration for one registered target.
 *
 * [capabilitiesEndpoint] is only used by the Test Harness adapter. It is the registered path the Harness publishes its
 * capability declaration on, and it is required when a Profile selects that adapter. Leaving it unset keeps a Profile
 * on the original Target-owned scenario contract.
 */
data class StockConcurrencyScenarioProfile(
    val endpoint: String,
    val capabilitiesEndpoint: String? = null,
    val maxStock: Int = 10_000,
    val maxRequestCount: Int = 100_000,
    val maxConcurrency: Int = 1_000,
    val maxQuantityPerRequest: Int = 100,
    val executionTimeout: Duration = Duration.ofMinutes(DEFAULT_EXECUTION_TIMEOUT_MINUTES),
)

private const val DEFAULT_EXECUTION_TIMEOUT_MINUTES = 15L

data class TargetExperimentProfile(
    val targetSystemId: String,
    val adapterId: String,
    val executionEnabled: Boolean,
    val hostResourceGroup: String,
    val stockConcurrency: StockConcurrencyScenarioProfile,
)
