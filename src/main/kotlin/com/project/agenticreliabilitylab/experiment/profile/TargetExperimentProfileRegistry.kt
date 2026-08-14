package com.project.agenticreliabilitylab.experiment.profile

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyScenarioProfile
import com.project.agenticreliabilitylab.experiment.domain.TargetExperimentProfile
import com.project.agenticreliabilitylab.experiment.application.port.TargetExperimentProfileCatalog
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("arl.experiment-targets")
data class TargetExperimentProfileProperties(
    val registrations: List<TargetExperimentRegistration> = emptyList(),
)

data class TargetExperimentRegistration(
    val targetSystemId: String,
    val adapterId: String,
    val executionEnabled: Boolean = false,
    val hostResourceGroup: String,
    val stockConcurrency: StockConcurrencyScenarioProfile? = null,
)

class TargetExperimentProfileRegistry(
    properties: TargetExperimentProfileProperties,
) : TargetExperimentProfileCatalog {
    private val profiles: Map<String, TargetExperimentProfile>

    init {
        val duplicateIds = properties.registrations.groupingBy { it.targetSystemId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate target experiment profile ids: ${duplicateIds.sorted().joinToString()}"
        }

        profiles = properties.registrations.associate { registration ->
            require(TARGET_ID_PATTERN.matches(registration.targetSystemId)) {
                "Target experiment profile id '${registration.targetSystemId}' is invalid"
            }
            require(ADAPTER_ID_PATTERN.matches(registration.adapterId)) {
                "Target experiment adapter id '${registration.adapterId}' is invalid"
            }
            require(HOST_RESOURCE_GROUP_PATTERN.matches(registration.hostResourceGroup)) {
                "Target experiment host resource group is invalid"
            }
            val stockConcurrency = registration.stockConcurrency
            if (stockConcurrency != null) {
                require(stockConcurrency.endpoint.isSafeRelativePath()) {
                    "STOCK_CONCURRENCY endpoint must be an absolute path on the registered target origin"
                }
                require(
                    stockConcurrency.maxStock > 0 &&
                        stockConcurrency.maxRequestCount > 0 &&
                        stockConcurrency.maxConcurrency > 0 &&
                        stockConcurrency.maxQuantityPerRequest > 0,
                ) {
                    "STOCK_CONCURRENCY limits must be positive"
                }
                require(!stockConcurrency.executionTimeout.isNegative && !stockConcurrency.executionTimeout.isZero) {
                    "STOCK_CONCURRENCY execution timeout must be positive"
                }
            }
            registration.targetSystemId to TargetExperimentProfile(
                targetSystemId = registration.targetSystemId,
                adapterId = registration.adapterId,
                executionEnabled = registration.executionEnabled,
                hostResourceGroup = registration.hostResourceGroup,
                stockConcurrency = stockConcurrency ?: throw IllegalArgumentException(
                    "Target experiment profile '${registration.targetSystemId}' must declare a STOCK_CONCURRENCY scenario in Phase 1",
                ),
            )
        }
    }

    fun find(targetSystemId: String): TargetExperimentProfile? = profiles[targetSystemId]

    override fun requireStockConcurrency(targetSystemId: String): TargetExperimentProfile =
        find(targetSystemId)
            ?: throw TargetExperimentProfileNotFoundException(targetSystemId, ExperimentType.STOCK_CONCURRENCY)

    private fun String.isSafeRelativePath(): Boolean =
        startsWith('/') && !startsWith("//") && !contains('?') && !contains('#') && split('/').none { it == ".." }

    private companion object {
        val TARGET_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,99}")
        val ADAPTER_ID_PATTERN = Regex("[A-Z][A-Z0-9_]{2,99}")
        val HOST_RESOURCE_GROUP_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,119}")
    }
}

class TargetExperimentProfileNotFoundException(
    targetSystemId: String,
    experimentType: ExperimentType,
) : com.project.agenticreliabilitylab.common.ResourceNotFoundException(
    "Target experiment profile for $experimentType",
    targetSystemId,
)
