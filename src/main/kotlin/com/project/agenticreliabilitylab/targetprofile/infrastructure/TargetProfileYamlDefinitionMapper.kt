package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyScenarioProfile
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.targetprofile.domain.ExperimentProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ReadOnlyOperationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import org.springframework.stereotype.Component
import java.time.Duration

/** Maps the whitelisted YAML tree into one target Version definition. */
@Component
class TargetProfileYamlDefinitionMapper(
    private val testSpecExecutionMapper: TestSpecExecutionYamlMapper,
) {
    fun map(root: Map<String, Any?>): TargetProfileDefinition {
        val arl = root.requiredMap("arl", TargetProfileYamlSchema.ARL_FIELDS)
        val target = arl.requiredMap("targets", TargetProfileYamlSchema.REGISTRATIONS_FIELD)
            .requiredList("registrations")
            .singleMap("arl.targets.registrations", TargetProfileYamlSchema.TARGET_FIELDS)
            .toTargetDefinition()
        val generic = arl.optionalMap("target-specs", TargetProfileYamlSchema.REGISTRATIONS_FIELD)
            ?.requiredList("registrations")
            ?.singleMatchingMap(
                "arl.target-specs.registrations",
                target.id,
                TargetProfileYamlSchema.ALL_REGISTRATION_FIELDS,
            )
            ?.toGenericDefinition(target.id)
        val experiment = arl.optionalMap("experiment-targets", TargetProfileYamlSchema.REGISTRATIONS_FIELD)
            ?.requiredList("registrations")
            ?.singleMatchingMap(
                "arl.experiment-targets.registrations",
                target.id,
                TargetProfileYamlSchema.ALL_REGISTRATION_FIELDS,
            )
            ?.toExperimentDefinition(target.id)
        val testSpecExecution = arl.optionalMap(
            "test-spec-execution",
            TargetProfileYamlSchema.REGISTRATIONS_FIELD,
        )
            ?.requiredList("registrations")
            ?.singleMatchingMap(
                "arl.test-spec-execution.registrations",
                target.id,
                TargetProfileYamlSchema.ALL_REGISTRATION_FIELDS,
            )
            ?.let { registration -> testSpecExecutionMapper.map(registration, target.id) }
        return TargetProfileDefinition(target, generic, experiment, testSpecExecution)
    }

    private fun Map<String, Any?>.toTargetDefinition(): TargetRegistrationDefinition {
        ensureOnly("target registration", TargetProfileYamlSchema.TARGET_FIELDS)
        return TargetRegistrationDefinition(
            id = requiredString("id"),
            name = requiredString("name"),
            adapterType = requiredString("adapter-type"),
            environment = enumValue("environment"),
            baseUrl = requiredString("base-url"),
            allowedOrigin = requiredString("allowed-origin"),
            allowedCidrs = requiredStringList("allowed-cidrs").toSet(),
            healthPath = requiredString("health-path"),
            openApiPath = optionalString("openapi-path"),
            openApiPaths = optionalStringList("openapi-paths") ?: emptyList(),
            sourceRepository = requiredString("source-repository"),
            identityVerification = enumValue("identity-verification"),
            capabilities = requiredStringList("capabilities").mapTo(linkedSetOf(), TargetCapability::valueOf),
            enabled = optionalBoolean("enabled") ?: true,
        )
    }

    private fun Map<String, Any?>.toGenericDefinition(targetSystemId: String): GenericHttpProfileDefinition {
        ensureOnly("Target Spec", TargetProfileYamlSchema.GENERIC_FIELDS)
        require(requiredString("target-system-id") == targetSystemId) {
            "Target Spec registration must match target '$targetSystemId'"
        }
        return GenericHttpProfileDefinition(
            executionEnabled = optionalBoolean("execution-enabled") ?: false,
            hostResourceGroup = optionalString("host-resource-group") ?: targetSystemId,
            maxBatchSize = optionalInt("max-batch-size") ?: DEFAULT_MAX_BATCH_SIZE,
            requestTimeout = optionalDuration("request-timeout") ?: DEFAULT_REQUEST_TIMEOUT,
            readOnlyOperations = optionalList("read-only-operations")?.mapIndexed { index, value ->
                value.yamlMap(
                    "read-only-operations[$index]",
                    TargetProfileYamlSchema.OPERATION_FIELDS,
                ).toOperationDefinition()
            } ?: emptyList(),
            failureInjectionPlanningEnabled = optionalBoolean("failure-injection-planning-enabled") ?: false,
            failureInjectionCandidates = optionalList("failure-injection-candidates")?.mapIndexed { index, value ->
                value.yamlMap(
                    "failure-injection-candidates[$index]",
                    TargetProfileYamlSchema.FAILURE_CANDIDATE_FIELDS,
                ).toFailureInjectionCandidate(targetSystemId)
            } ?: emptyList(),
        )
    }

    private fun Map<String, Any?>.toOperationDefinition(): ReadOnlyOperationDefinition =
        ReadOnlyOperationDefinition(
            id = requiredString("id"),
            title = requiredString("title"),
            description = optionalString("description").orEmpty(),
            path = requiredString("path"),
            operationId = optionalString("operation-id"),
            expectedStatusCodes = optionalIntList("expected-status-codes")?.toSet() ?: setOf(HTTP_OK),
        )

    private fun Map<String, Any?>.toFailureInjectionCandidate(targetSystemId: String): FailureInjectionCandidate =
        FailureInjectionCandidate(
            id = requiredString("id"),
            targetSystemId = targetSystemId,
            type = enumValue("type"),
            risk = enumValue("risk"),
            title = requiredString("title"),
            description = optionalString("description").orEmpty(),
            recoveryExpectation = requiredString("recovery-expectation"),
        )

    private fun Map<String, Any?>.toExperimentDefinition(targetSystemId: String): ExperimentProfileDefinition {
        ensureOnly("Target experiment profile", TargetProfileYamlSchema.EXPERIMENT_FIELDS)
        require(requiredString("target-system-id") == targetSystemId) {
            "Target experiment profile registration must match target '$targetSystemId'"
        }
        val stock = requiredMap("stock-concurrency", TargetProfileYamlSchema.STOCK_CONCURRENCY_FIELDS)
        return ExperimentProfileDefinition(
            adapterId = requiredString("adapter-id"),
            executionEnabled = optionalBoolean("execution-enabled") ?: false,
            hostResourceGroup = requiredString("host-resource-group"),
            stockConcurrency = StockConcurrencyScenarioProfile(
                endpoint = stock.requiredString("endpoint"),
                capabilitiesEndpoint = stock.optionalString("capabilities-endpoint"),
                maxStock = stock.optionalInt("max-stock") ?: DEFAULT_MAX_STOCK,
                maxRequestCount = stock.optionalInt("max-request-count") ?: DEFAULT_MAX_REQUEST_COUNT,
                maxConcurrency = stock.optionalInt("max-concurrency") ?: DEFAULT_MAX_CONCURRENCY,
                maxQuantityPerRequest = stock.optionalInt("max-quantity-per-request") ?: DEFAULT_MAX_QUANTITY,
                executionTimeout = stock.optionalDuration("execution-timeout") ?: DEFAULT_EXECUTION_TIMEOUT,
            ),
        )
    }

    private companion object {
        const val HTTP_OK = 200
        const val DEFAULT_MAX_BATCH_SIZE = 10
        const val DEFAULT_MAX_STOCK = 10_000
        const val DEFAULT_MAX_REQUEST_COUNT = 100_000
        const val DEFAULT_MAX_CONCURRENCY = 1_000
        const val DEFAULT_MAX_QUANTITY = 100
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        val DEFAULT_EXECUTION_TIMEOUT: Duration = Duration.ofMinutes(15)
    }
}
