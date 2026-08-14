package com.project.agenticreliabilitylab.experiment.adapter

import com.project.agenticreliabilitylab.experiment.domain.ExperimentTargetAdapter
import com.project.agenticreliabilitylab.experiment.domain.ExternalOperationOutcomeUnknownException
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyExecutionResult
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyTargetExecutionRequest
import com.project.agenticreliabilitylab.experiment.domain.TargetResource
import com.project.agenticreliabilitylab.target.http.PinnedTargetHttpTransport
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
        require(uri.normalizedOrigin() == request.target.allowedOrigin.normalizedOrigin()) {
            "Registered STOCK_CONCURRENCY endpoint is outside the target allowed origin"
        }
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
            successCount = result.requiredNonNegativeInt("successCount"),
            failureCount = result.requiredNonNegativeInt("failureCount"),
            oversellCount = result.requiredNonNegativeInt("oversellCount"),
            finalRedisStock = result.path("finalRedisStock").asString().toIntOrNull(),
            finalDbStock = result.path("finalDbStock").asString().toIntOrNull(),
            durationSeconds = result.requiredNonNegativeLong("durationSeconds"),
            cleanupVerified = root.path("cleanup").path("status").asString() == "VERIFIED",
            artifactReference = artifact.path("reference").asString().takeIf { ARTIFACT_REFERENCE_PATTERN.matches(it) }
                ?: "target-inline-result:${request.runId}",
            artifactChecksum = artifact.path("checksum").asString().takeIf { it.isNotBlank() } ?: body.sha256(),
            resources = root.path("resources").toResources(request.runId.toString()),
        )
    }

    private fun tools.jackson.databind.JsonNode.requiredNonNegativeInt(field: String): Int =
        path(field).asString().toIntOrNull()?.takeIf { it >= 0 }
            ?: throw ExternalOperationOutcomeUnknownException("Target Scenario result field '$field' must be a non-negative integer")

    private fun tools.jackson.databind.JsonNode.requiredNonNegativeLong(field: String): Long =
        path(field).asString().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw ExternalOperationOutcomeUnknownException("Target Scenario result field '$field' must be a non-negative integer")

    private fun tools.jackson.databind.JsonNode.toResources(defaultNamespace: String): List<TargetResource> {
        if (!isArray) {
            return emptyList()
        }
        return buildList {
            for (resource in this@toResources) {
            val type = resource.path("type").asString()
            val id = resource.path("id").asString()
            if (!RESOURCE_VALUE_PATTERN.matches(type) || !RESOURCE_VALUE_PATTERN.matches(id)) {
                throw ExternalOperationOutcomeUnknownException(
                    "Target Scenario resource type and id must be non-empty safe identifiers",
                )
            }
            val namespace = resource.path("namespace").asString().takeIf { it.isNotBlank() } ?: defaultNamespace
            if (!RESOURCE_VALUE_PATTERN.matches(namespace)) {
                throw ExternalOperationOutcomeUnknownException(
                    "Target Scenario resource namespace must be a non-empty safe identifier",
                )
            }
            add(TargetResource(
                type = type,
                id = id,
                namespace = namespace,
            ))
            }
        }
    }

    private fun URI.normalizedOrigin(): String {
        val effectivePort = when {
            port >= 0 -> port
            scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort"
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ADAPTER_ID = "HTTP_SCENARIO_V1"
        val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED")
        val OPERATION_ID_PATTERN = Regex("[A-Za-z0-9._:@/-]{1,300}")
        val RESOURCE_VALUE_PATTERN = Regex("[A-Za-z0-9._:@/-]{1,300}")
        val ARTIFACT_REFERENCE_PATTERN = Regex("[A-Za-z0-9._:@/-]{1,500}")
    }
}
