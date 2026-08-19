package com.project.agenticreliabilitylab.experiment.adapter

import com.project.agenticreliabilitylab.experiment.domain.ExperimentTargetAdapter
import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.experiment.domain.ExternalOperationOutcomeUnknownException
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyExecutionResult
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyScenarioProfile
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyTargetExecutionRequest
import com.project.agenticreliabilitylab.experiment.domain.TargetPreflightFailedException
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.http.PinnedTargetHttpTransport
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Runs a state-changing Experiment through a Target's general Test Harness.
 *
 * This is the ARL side of the Harness contract. The Harness itself is a Target-owned control surface, so this class
 * implements the existing [ExperimentTargetAdapter] and nothing else: run state, the action journal, fencing, the
 * resource ledger, cleanup retries and recovery all stay with the Experiment engine that already owns them, and a
 * Target that publishes the standard contract needs no adapter of its own.
 *
 * Fixture preparation, workload, observation and cleanup happen inside one terminal call, so ARL never has to track a
 * second, Harness-side run state machine. The Harness reports what it observed; whether those observations satisfy the
 * Experiment invariants is decided afterwards by the existing oracle.
 */
@Component
class HttpTestHarnessTargetAdapter(
    private val objectMapper: ObjectMapper,
    private val targetHttpTransport: PinnedTargetHttpTransport,
    private val capabilityClient: TestHarnessCapabilityClient,
) : ExperimentTargetAdapter {
    override val adapterId: String = ADAPTER_ID

    override fun executeStockConcurrency(
        request: StockConcurrencyTargetExecutionRequest,
    ): StockConcurrencyExecutionResult {
        val scenario = request.profile.stockConcurrency
        val capabilitiesEndpoint = scenario.capabilitiesEndpoint
            ?: throw TargetPreflightFailedException(
                "Target Profile '${request.profile.targetSystemId}' selects $ADAPTER_ID with no capabilities endpoint",
            )
        if (request.target.environment !in EXECUTABLE_ENVIRONMENTS) {
            throw TargetPreflightFailedException("Test Harness execution is limited to LOCAL and TEST targets")
        }
        val capability = capabilityClient.discover(
            target = request.target,
            capabilitiesEndpoint = capabilitiesEndpoint,
            experimentType = ExperimentType.STOCK_CONCURRENCY,
            timeout = minOf(scenario.executionTimeout, CAPABILITY_DISCOVERY_TIMEOUT),
        )
        requireUsable(capability, request, scenario)
        return execute(request, capability)
    }

    /**
     * Confirms the declaration permits this exact run.
     *
     * Bounds are taken as the smaller of the Profile and the declaration, so a Harness that claims a larger allowance
     * than the Profile grants cannot raise the ceiling; it can only lower it.
     */
    private fun requireUsable(
        capability: TestHarnessCapability,
        request: StockConcurrencyTargetExecutionRequest,
        scenario: StockConcurrencyScenarioProfile,
    ) {
        val violations = buildList {
            if (!capability.cleanupVerificationRequired) {
                add("must verify cleanup before it may run")
            }
            if (capability.invariantIds.isEmpty()) {
                add("must declare the invariants it observes")
            }
            if (request.target.environment.name !in capability.environments) {
                add("is not declared for ${request.target.environment}")
            }
        }
        if (violations.isNotEmpty()) {
            throw TargetPreflightFailedException(
                "Test Harness capability '${capability.id}' ${violations.joinToString(", and ")}",
            )
        }
        requireWithinBounds(capability, request.parameters, scenario)
    }

    private fun requireWithinBounds(
        capability: TestHarnessCapability,
        parameters: StockConcurrencyParameters,
        scenario: StockConcurrencyScenarioProfile,
    ) {
        val exceeded = buildList {
            if (parameters.stock > minOf(scenario.maxStock, capability.maxStock)) add("stock")
            if (parameters.requestCount > minOf(scenario.maxRequestCount, capability.maxRequestCount)) {
                add("requestCount")
            }
            if (parameters.concurrency > minOf(scenario.maxConcurrency, capability.maxConcurrency)) add("concurrency")
            val maxQuantity = minOf(scenario.maxQuantityPerRequest, capability.maxQuantityPerRequest)
            if (parameters.quantityPerRequest > maxQuantity) add("quantityPerRequest")
        }
        if (exceeded.isNotEmpty()) {
            throw TargetPreflightFailedException(
                "Requested ${exceeded.joinToString()} exceeds the effective Test Harness bound",
            )
        }
    }

    private fun execute(
        request: StockConcurrencyTargetExecutionRequest,
        capability: TestHarnessCapability,
    ): StockConcurrencyExecutionResult {
        val scenario = request.profile.stockConcurrency
        val uri = request.target.baseUri.resolve(scenario.endpoint)
        requireRegisteredOrigin(uri, request.target, "Test Harness execution endpoint")
        val durableActionKey = "${request.runId}:${request.actionId}"
        val body = objectMapper.writeValueAsString(
            mapOf(
                "contractVersion" to TestHarnessCapability.CONTRACT_VERSION,
                "capabilityId" to capability.id,
                "capabilityVersion" to capability.version,
                "experimentType" to ExperimentType.STOCK_CONCURRENCY.name,
                "runId" to request.runId,
                "actionId" to request.actionId,
                "idempotencyKey" to durableActionKey,
                "parameters" to mapOf(
                    "stock" to request.parameters.stock,
                    "requestCount" to request.parameters.requestCount,
                    "concurrency" to request.parameters.concurrency,
                    "quantityPerRequest" to request.parameters.quantityPerRequest,
                ),
            ),
        )
        val response = try {
            targetHttpTransport.send(
                target = request.target,
                uri = uri,
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Idempotency-Key" to durableActionKey,
                ),
                body = body.toByteArray(StandardCharsets.UTF_8),
                timeout = scenario.executionTimeout,
            )
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            throw ExternalOperationOutcomeUnknownException(
                "Test Harness request did not return a durable result: ${exception.javaClass.simpleName}",
                exception,
            )
        }
        if (response.statusCode !in SUCCESS_STATUS_CODES) {
            throw ExternalOperationOutcomeUnknownException(
                "Test Harness returned HTTP ${response.statusCode} without a successful result contract",
            )
        }
        return parse(String(response.body, StandardCharsets.UTF_8), request, capability)
    }

    /**
     * Reads the terminal result contract.
     *
     * Every rejection here means the same thing to the engine - the Harness may have mutated the Target but did not
     * return a result ARL can trust - so each distinct contract defect is thrown as it is found rather than collected.
     */
    @Suppress("ThrowsCount")
    private fun parse(
        body: String,
        request: StockConcurrencyTargetExecutionRequest,
        capability: TestHarnessCapability,
    ): StockConcurrencyExecutionResult {
        val root = try {
            objectMapper.readTree(body)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            throw ExternalOperationOutcomeUnknownException(
                "Test Harness returned an unreadable result contract: ${exception.javaClass.simpleName}",
                exception,
            )
        }
        val status = root.path("status").asString()
        if (status !in TERMINAL_STATUSES) {
            throw ExternalOperationOutcomeUnknownException(
                "$ADAPTER_ID requires a terminal result status, but the Harness returned '$status'",
            )
        }
        val observations = root.path("observations")
        val operationId = root.path("operationId").asString().takeIf(OPERATION_ID_PATTERN::matches)
            ?: throw ExternalOperationOutcomeUnknownException("Test Harness result has no operationId")
        return StockConcurrencyExecutionResult(
            targetOperationId = operationId,
            executionStatus = status,
            message = root.path("message").asString("Test Harness execution completed"),
            productId = root.path("fixture").safeIdentifierOrNull("productId"),
            successCount = observations.requiredNonNegativeInt("successCount", OBSERVATION_LABEL),
            failureCount = observations.requiredNonNegativeInt("failureCount", OBSERVATION_LABEL),
            oversellCount = observations.requiredNonNegativeInt("oversellCount", OBSERVATION_LABEL),
            finalRedisStock = observations.path("finalRedisStock").asString().toIntOrNull(),
            finalDbStock = observations.path("finalDbStock").asString().toIntOrNull(),
            durationSeconds = observations.requiredNonNegativeLong("durationSeconds", OBSERVATION_LABEL),
            cleanupVerified = root.path("cleanup").path("status").asString() == CLEANUP_VERIFIED,
            artifactReference = "test-harness:${capability.id}:${request.runId}",
            artifactChecksum = body.sha256Hex(),
            resources = root.path("resources").toResources(capability.testNamespace),
        )
    }

    private companion object {
        const val ADAPTER_ID = "TEST_HARNESS_V1"
        const val CLEANUP_VERIFIED = "VERIFIED"
        const val OBSERVATION_LABEL = "Test Harness observation"
        val CAPABILITY_DISCOVERY_TIMEOUT: Duration = Duration.ofSeconds(10)
        val EXECUTABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
        val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED")
        val SUCCESS_STATUS_CODES = 200..299
        val OPERATION_ID_PATTERN = Regex("[A-Za-z0-9._:@/-]{1,300}")
    }
}
