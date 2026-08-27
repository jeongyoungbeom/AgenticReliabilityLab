package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.targetprofile.domain.ExperimentProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileFaultInjectionDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** Renders an imported Profile back to a complete, parseable YAML document without exposing credentials. */
@Component
class TargetProfileYamlRenderer {
    fun render(definition: TargetProfileDefinition): String {
        val target = definition.target
        val arl = linkedMapOf<String, Any?>(
            "targets" to linkedMapOf("registrations" to listOf(target.toYaml())),
        )
        definition.genericHttp?.let { generic ->
            arl["target-specs"] = linkedMapOf("registrations" to listOf(generic.toYaml(target.id)))
        }
        definition.experiment?.let { experiment ->
            arl["experiment-targets"] = linkedMapOf("registrations" to listOf(experiment.toYaml(target.id)))
        }
        definition.testSpecExecution?.let { execution ->
            arl["test-spec-execution"] = linkedMapOf("registrations" to listOf(execution.toYaml(target.id)))
        }
        return Yaml(blockStyle()).dump(linkedMapOf("arl" to arl))
    }

    private fun TargetRegistrationDefinition.toYaml(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "adapter-type" to adapterType,
        "environment" to environment.name,
        "base-url" to baseUrl,
        "allowed-origin" to allowedOrigin,
        "allowed-cidrs" to allowedCidrs.sorted(),
        "health-path" to healthPath,
        "openapi-path" to openApiPath,
        "openapi-paths" to openApiPaths.orEmpty(),
        "source-repository" to sourceRepository,
        "identity-verification" to identityVerification.name,
        "capabilities" to capabilities.map { capability -> capability.name }.sorted(),
        "enabled" to enabled,
    ).filterValues { value -> value != null }

    private fun GenericHttpProfileDefinition.toYaml(targetSystemId: String): Map<String, Any?> = linkedMapOf(
        "target-system-id" to targetSystemId,
        "execution-enabled" to executionEnabled,
        "host-resource-group" to hostResourceGroup,
        "max-batch-size" to maxBatchSize,
        "request-timeout" to requestTimeout.toString(),
        "read-only-operations" to readOnlyOperations.map { operation ->
            linkedMapOf(
                "id" to operation.id,
                "title" to operation.title,
                "description" to operation.description,
                "path" to operation.path,
                "operation-id" to operation.operationId,
                "expected-status-codes" to operation.expectedStatusCodes.sorted(),
            ).filterValues { value -> value != null }
        },
        "failure-injection-planning-enabled" to failureInjectionPlanningEnabled,
        "failure-injection-candidates" to failureInjectionCandidates.map { candidate ->
            linkedMapOf(
                "id" to candidate.id,
                "type" to candidate.type.name,
                "risk" to candidate.risk.name,
                "title" to candidate.title,
                "description" to candidate.description,
                "recovery-expectation" to candidate.recoveryExpectation,
            )
        },
    )

    private fun ExperimentProfileDefinition.toYaml(targetSystemId: String): Map<String, Any?> = linkedMapOf(
        "target-system-id" to targetSystemId,
        "adapter-id" to adapterId,
        "execution-enabled" to executionEnabled,
        "host-resource-group" to hostResourceGroup,
        "stock-concurrency" to linkedMapOf(
            "endpoint" to stockConcurrency.endpoint,
            "capabilities-endpoint" to stockConcurrency.capabilitiesEndpoint,
            "max-stock" to stockConcurrency.maxStock,
            "max-request-count" to stockConcurrency.maxRequestCount,
            "max-concurrency" to stockConcurrency.maxConcurrency,
            "max-quantity-per-request" to stockConcurrency.maxQuantityPerRequest,
            "execution-timeout" to stockConcurrency.executionTimeout.toString(),
        ).filterValues { value -> value != null },
    )

    private fun TestSpecExecutionProfileDefinition.toYaml(targetSystemId: String): Map<String, Any?> = linkedMapOf(
        "target-system-id" to targetSystemId,
        "execution-enabled" to executionEnabled,
        "allowed-calls" to allowedCalls.map { call -> call.toYaml() },
        "auth-profiles" to authProfiles.sorted(),
        "observation-sources" to observationSources.map { source -> source.toYaml() },
        "supported-faults" to supportedFaults.sorted(),
        "infrastructure-targets" to infrastructureTargets.sorted(),
        "max-concurrency" to maxConcurrency,
        "max-request-count" to maxRequestCount,
        "max-trials" to maxTrials,
        "state-changing-allowed" to stateChangingAllowed,
    ).also { document ->
        reset?.let { value -> document["reset"] = value.toYaml() }
        faultInjection?.let { value -> document["fault-injection"] = value.toYaml() }
    }

    private fun ProfileHttpCallDefinition.toYaml(): Map<String, Any?> = linkedMapOf(
        "method" to method,
        "path" to path,
        "auth-profile" to authProfile,
        "operation-id" to operationId,
    ).filterValues { value -> value != null }

    private fun ProfileObservationSourceDefinition.toYaml(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "kind" to kind.name,
        "endpoint" to endpoint,
        "fields" to fields.sorted(),
        "queries" to queries.takeIf { values -> values.isNotEmpty() },
        "auth-profile" to authProfile,
    ).filterValues { value -> value != null }

    private fun ProfileResetDefinition.toYaml(): Map<String, Any?> = linkedMapOf(
        "method" to method.name,
        "hook" to hook?.toYaml(),
        "expected-duration" to expectedDuration.toString(),
        "verifications" to verifications.map { verification ->
            linkedMapOf(
                "id" to verification.id,
                "call" to verification.call.toYaml(),
                "expr" to verification.expression,
                "condition" to verification.condition,
                "read-at" to linkedMapOf(
                    "rule" to verification.readTiming.rule.name,
                    "max-wait" to verification.readTiming.maxWait.toString(),
                    "interval" to verification.readTiming.interval.toString(),
                ),
            )
        },
    ).filterValues { value -> value != null }

    private fun ProfileFaultInjectionDefinition.toYaml(): Map<String, Any?> = linkedMapOf(
        "inject-endpoint" to injectEndpoint.toYaml(),
        "release-endpoint" to releaseEndpoint.toYaml(),
        "max-ttl" to maxTtl.toString(),
    )

    private fun blockStyle() = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        indent = 2
    }
}
