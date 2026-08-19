package com.project.agenticreliabilitylab.experiment.adapter

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.experiment.domain.TargetPreflightFailedException
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.http.PinnedTargetHttpTransport
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Reads a Target Test Harness capability declaration.
 *
 * The declaration is untrusted input: it is read only through the registered origin, and every field is validated
 * before it can influence an execution. A Harness that answers with anything unexpected yields no capability at all
 * rather than a partially trusted one.
 */
@Component
class TestHarnessCapabilityClient(
    private val objectMapper: ObjectMapper,
    private val targetHttpTransport: PinnedTargetHttpTransport,
) {
    fun discover(
        target: RegisteredTarget,
        capabilitiesEndpoint: String,
        experimentType: ExperimentType,
        timeout: Duration,
    ): TestHarnessCapability {
        val uri = target.baseUri.resolve(capabilitiesEndpoint)
        requireRegisteredOrigin(uri, target, "Test Harness capabilities endpoint")
        val root = readDeclaration(target, uri, timeout)
        if (root.path(CONTRACT_VERSION_FIELD).asString() != TestHarnessCapability.CONTRACT_VERSION) {
            throw TargetPreflightFailedException(
                "Test Harness must declare contractVersion ${TestHarnessCapability.CONTRACT_VERSION}",
            )
        }
        return selectCapability(root, experimentType)
    }

    /**
     * Any failure here means the capability declaration could not be read, and reading it has no side effect.
     *
     * The catch is deliberately broad: whatever the transport or parser raises, the safe answer is the same, and
     * letting an unexpected type escape would make the engine treat a read as a possible Target mutation.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun readDeclaration(target: RegisteredTarget, uri: URI, timeout: Duration): JsonNode {
        val response = try {
            targetHttpTransport.send(
                target = target,
                uri = uri,
                method = "GET",
                headers = mapOf("Accept" to "application/json"),
                body = ByteArray(0),
                timeout = timeout,
            )
        } catch (exception: Exception) {
            throw TargetPreflightFailedException(
                "Test Harness capabilities request failed: ${exception.javaClass.simpleName}",
                exception,
            )
        }
        if (response.statusCode !in SUCCESS_STATUS_CODES) {
            throw TargetPreflightFailedException(
                "Test Harness capabilities endpoint returned HTTP ${response.statusCode}",
            )
        }
        return try {
            objectMapper.readTree(String(response.body, StandardCharsets.UTF_8))
        } catch (exception: Exception) {
            throw TargetPreflightFailedException(
                "Test Harness capabilities response was unreadable: ${exception.javaClass.simpleName}",
                exception,
            )
        }
    }

    /** Exactly one declaration may claim an Experiment type, so the choice of what runs is never ambiguous. */
    private fun selectCapability(root: JsonNode, experimentType: ExperimentType): TestHarnessCapability {
        val matching = root.path(CAPABILITIES_FIELD).values()
            .filter { capability -> capability.path("experimentType").asString() == experimentType.name }
        if (matching.size != 1) {
            throw TargetPreflightFailedException(
                "Test Harness must declare exactly one $experimentType capability but declared ${matching.size}",
            )
        }
        return matching.first().toCapability(experimentType)
    }

    private fun JsonNode.toCapability(experimentType: ExperimentType): TestHarnessCapability {
        val limits = path("limits")
        return TestHarnessCapability(
            id = path("id").asString().requireIdentifier("capability id"),
            version = path("version").asString().requireIdentifier("capability version"),
            experimentType = experimentType,
            environments = path("environments").values().mapTo(linkedSetOf()) { node -> node.asString() },
            testNamespace = path("testNamespace").asString().requireIdentifier("test namespace"),
            maxStock = limits.requiredPositiveInt("maxStock"),
            maxRequestCount = limits.requiredPositiveInt("maxRequestCount"),
            maxConcurrency = limits.requiredPositiveInt("maxConcurrency"),
            maxQuantityPerRequest = limits.requiredPositiveInt("maxQuantityPerRequest"),
            invariantIds = path("invariantIds").values().map { node -> node.asString() }
                .filter(String::isNotBlank),
            cleanupVerification = path("cleanupVerification").asString(),
        )
    }

    private fun JsonNode.requiredPositiveInt(field: String): Int =
        path(field).asString().toIntOrNull()?.takeIf { value -> value > 0 }
            ?: throw TargetPreflightFailedException("Capability limit '$field' must be a positive integer")

    private fun String.requireIdentifier(label: String): String =
        takeIf(IDENTIFIER_PATTERN::matches)
            ?: throw TargetPreflightFailedException("Test Harness $label must be a safe identifier")

    private companion object {
        const val CONTRACT_VERSION_FIELD = "contractVersion"
        const val CAPABILITIES_FIELD = "capabilities"
        val SUCCESS_STATUS_CODES = 200..299
        val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,100}")
    }
}
