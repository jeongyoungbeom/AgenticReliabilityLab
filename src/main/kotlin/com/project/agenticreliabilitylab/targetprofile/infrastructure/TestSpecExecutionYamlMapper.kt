package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.domain.ProfileFaultInjectionDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileReadTimingDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDocumentException
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import org.springframework.stereotype.Component
import java.time.Duration

/** Maps the isolated `test-spec-execution` section of a Target Profile. */
@Component
class TestSpecExecutionYamlMapper {
    fun map(registration: Map<String, Any?>, targetSystemId: String): TestSpecExecutionProfileDefinition {
        registration.ensureOnly("Test specification execution", TargetProfileYamlSchema.TEST_SPEC_EXECUTION_FIELDS)
        require(registration.requiredString("target-system-id") == targetSystemId) {
            "Test specification execution registration must match target '$targetSystemId'"
        }
        return TestSpecExecutionProfileDefinition(
            executionEnabled = registration.optionalBoolean("execution-enabled") ?: false,
            allowedCalls = registration.optionalList("allowed-calls")?.mapIndexed { index, value ->
                value.yamlMap("allowed-calls[$index]", TargetProfileYamlSchema.SPEC_CALL_FIELDS).toCall()
            } ?: emptyList(),
            authProfiles = registration.optionalStringList("auth-profiles")?.toSet() ?: emptySet(),
            observationSources = registration.optionalList("observation-sources")?.mapIndexed { index, value ->
                value.yamlMap(
                    "observation-sources[$index]",
                    TargetProfileYamlSchema.OBSERVATION_SOURCE_FIELDS,
                ).toObservationSource()
            } ?: emptyList(),
            supportedFaults = registration.optionalStringList("supported-faults")?.toSet() ?: emptySet(),
            infrastructureTargets =
                registration.optionalStringList("infrastructure-targets")?.toSet() ?: emptySet(),
            maxConcurrency = registration.optionalInt("max-concurrency") ?: DEFAULT_MAX_CONCURRENCY,
            maxRequestCount = registration.optionalInt("max-request-count") ?: DEFAULT_MAX_REQUEST_COUNT,
            maxTrials = registration.optionalInt("max-trials") ?: DEFAULT_MAX_TRIALS,
            stateChangingAllowed = registration.optionalBoolean("state-changing-allowed") ?: false,
            reset = registration.optionalMap("reset", TargetProfileYamlSchema.RESET_FIELDS)?.toReset(),
            faultInjection = registration.optionalMap(
                "fault-injection",
                TargetProfileYamlSchema.FAULT_INJECTION_FIELDS,
            )?.toFaultInjection(),
        )
    }

    private fun Map<String, Any?>.toCall(): ProfileHttpCallDefinition = ProfileHttpCallDefinition(
        method = requiredString("method").uppercase(),
        path = requiredString("path"),
        authProfile = optionalString("auth-profile"),
    )

    private fun Map<String, Any?>.toObservationSource() = ProfileObservationSourceDefinition(
        name = requiredString("name"),
        kind = enumValue<ProfileObservationSourceKind>("kind"),
        endpoint = requiredString("endpoint"),
        fields = requiredStringList("fields").toSet(),
        queries = optionalStringMap("queries") ?: emptyMap(),
        authProfile = optionalString("auth-profile"),
    )

    private fun Map<String, Any?>.toFaultInjection() = ProfileFaultInjectionDefinition(
        injectEndpoint = requiredMap("inject-endpoint", TargetProfileYamlSchema.SPEC_CALL_FIELDS).toCall(),
        releaseEndpoint = requiredMap("release-endpoint", TargetProfileYamlSchema.SPEC_CALL_FIELDS).toCall(),
        maxTtl = optionalDuration("max-ttl") ?: DEFAULT_MAX_FAULT_TTL,
    )

    private fun Map<String, Any?>.toReset(): ProfileResetDefinition {
        val method = enumValue<CleanupMethod>("method")
        return ProfileResetDefinition(
            method = method,
            hook = optionalMap("hook", TargetProfileYamlSchema.SPEC_CALL_FIELDS)?.toCall(),
            expectedDuration = optionalDuration("expected-duration")
                ?: if (method == CleanupMethod.NOT_REQUIRED) Duration.ZERO else DEFAULT_RESET_DURATION,
            verifications = optionalList("verifications")?.mapIndexed { index, value ->
                value.yamlMap(
                    "verifications[$index]",
                    TargetProfileYamlSchema.RESET_VERIFICATION_FIELDS,
                ).toResetVerification()
            } ?: emptyList(),
        )
    }

    private fun Map<String, Any?>.toResetVerification() = ProfileResetVerificationDefinition(
        id = requiredString("id"),
        call = requiredMap("call", TargetProfileYamlSchema.SPEC_CALL_FIELDS).toCall(),
        expression = requiredString("expr"),
        condition = requiredString("condition"),
        readTiming = optionalMap("read-at", TargetProfileYamlSchema.READ_TIMING_FIELDS)?.toReadTiming()
            ?: ProfileReadTimingDefinition(StabilityRule.IMMEDIATE, Duration.ZERO, Duration.ZERO),
    )

    private fun Map<String, Any?>.toReadTiming(): ProfileReadTimingDefinition {
        val rule = enumValue<StabilityRule>("rule")
        if (rule == StabilityRule.IMMEDIATE) {
            return ProfileReadTimingDefinition(rule, Duration.ZERO, Duration.ZERO)
        }
        val maxWait = optionalDuration("max-wait")
            ?: throw TargetProfileDocumentException("'max-wait' is required for a settling reset verification")
        return ProfileReadTimingDefinition(
            rule = rule,
            maxWait = maxWait,
            interval = optionalDuration("interval") ?: DEFAULT_READ_INTERVAL,
        )
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENCY = 10
        const val DEFAULT_MAX_REQUEST_COUNT = 100
        const val DEFAULT_MAX_TRIALS = 20
        val DEFAULT_READ_INTERVAL: Duration = Duration.ofMillis(500)
        val DEFAULT_RESET_DURATION: Duration = Duration.ofMinutes(2)
        val DEFAULT_MAX_FAULT_TTL: Duration = Duration.ofMinutes(5)
    }
}
