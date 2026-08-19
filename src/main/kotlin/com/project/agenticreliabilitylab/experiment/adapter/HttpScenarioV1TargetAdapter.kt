package com.project.agenticreliabilitylab.experiment.adapter

import com.project.agenticreliabilitylab.experiment.domain.ExperimentTargetAdapter
import com.project.agenticreliabilitylab.experiment.domain.ExternalOperationOutcomeUnknownException
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyExecutionResult
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyTargetExecutionRequest
import com.project.agenticreliabilitylab.target.http.PinnedTargetHttpTransport
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/**
 * Built-in adapter for a Target-owned HTTP Scenario endpoint. It accepts only
 * the endpoint registered in a Target Profile; caller supplied URLs are never used.
 */
@Component
class HttpScenarioV1TargetAdapter(
    private val objectMapper: ObjectMapper,
    private val targetHttpTransport: PinnedTargetHttpTransport,
) : ExperimentTargetAdapter {
    override val adapterId: String = ADAPTER_ID

    override fun executeStockConcurrency(
        request: StockConcurrencyTargetExecutionRequest,
    ): StockConcurrencyExecutionResult {
        val endpoint = request.profile.stockConcurrency.endpoint
        val uri = request.target.baseUri.resolve(endpoint)
        requireRegisteredOrigin(uri, request.target, "STOCK_CONCURRENCY endpoint")
        val durableActionKey = "${request.runId}:${request.actionId}"

        val requestBody = objectMapper.writeValueAsString(
            mapOf(
                "contractVersion" to "HTTP_SCENARIO_V1",
                "experimentType" to "STOCK_CONCURRENCY",
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
                body = requestBody.toByteArray(StandardCharsets.UTF_8),
                timeout = request.profile.stockConcurrency.executionTimeout,
            )
        } catch (exception: Exception) {
            throw ExternalOperationOutcomeUnknownException(
                "Target Scenario request did not return a durable result: ${exception.javaClass.simpleName}",
            )
        }
        if (response.statusCode !in 200..299) {
            throw ExternalOperationOutcomeUnknownException(
                "Target Scenario endpoint returned HTTP ${response.statusCode} without a successful result contract",
            )
        }

        val body = String(response.body, StandardCharsets.UTF_8)
        val root = try {
            objectMapper.readTree(body)
        } catch (exception: Exception) {
            throw ExternalOperationOutcomeUnknownException("Target Scenario returned an unreadable result contract: ${exception.javaClass.simpleName}")
        }
        val status = root.path("status").asString()
        if (status !in TERMINAL_STATUSES) {
            throw ExternalOperationOutcomeUnknownException(
                "HTTP_SCENARIO_V1 requires a terminal result status, but target returned '$status'",
            )
        }
        val result = root.path("result")
        val operationId = root.path("operationId").asString().takeIf { OPERATION_ID_PATTERN.matches(it) }
            ?: throw ExternalOperationOutcomeUnknownException("Target Scenario result has no operationId")
        val artifact = root.path("artifact")

        return StockConcurrencyExecutionResult(
            targetOperationId = operationId,
            executionStatus = status,
            message = root.path("message").asString("Target Scenario completed"),
            productId = result.path("productId").asString().takeIf { it.isNotBlank() },
            successCount = result.requiredNonNegativeInt("successCount", RESULT_LABEL),
            failureCount = result.requiredNonNegativeInt("failureCount", RESULT_LABEL),
            oversellCount = result.requiredNonNegativeInt("oversellCount", RESULT_LABEL),
            finalRedisStock = result.path("finalRedisStock").asString().toIntOrNull(),
            finalDbStock = result.path("finalDbStock").asString().toIntOrNull(),
            durationSeconds = result.requiredNonNegativeLong("durationSeconds", RESULT_LABEL),
            cleanupVerified = root.path("cleanup").path("status").asString() == "VERIFIED",
            artifactReference = artifact.path("reference").asString().takeIf { ARTIFACT_REFERENCE_PATTERN.matches(it) }
                ?: "target-inline-result:${request.runId}",
            artifactChecksum = artifact.path("checksum").asString().takeIf { it.isNotBlank() } ?: body.sha256Hex(),
            resources = root.path("resources").toResources(request.runId.toString()),
        )
    }

    private companion object {
        const val ADAPTER_ID = "HTTP_SCENARIO_V1"
        const val RESULT_LABEL = "Target Scenario result field"
        val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED")
        val OPERATION_ID_PATTERN = Regex("[A-Za-z0-9._:@/-]{1,300}")
        val ARTIFACT_REFERENCE_PATTERN = Regex("[A-Za-z0-9._:@/-]{1,500}")
    }
}
