package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileFaultInjectionDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import com.project.agenticreliabilitylab.testspec.domain.TraceScope
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

/** Validates the Profile-owned authority for declarative specification execution. */
// One check is added per Profile-declared capability; splitting further would separate a check from the field it
// validates.
@Suppress("TooManyFunctions")
@Component
class TestSpecExecutionProfileValidator {
    fun validate(profile: TestSpecExecutionProfileDefinition, target: TargetRegistrationDefinition) {
        require(profile.maxConcurrency in 1..MAX_CONCURRENCY) {
            "Test specification max concurrency must be between 1 and $MAX_CONCURRENCY"
        }
        require(profile.maxRequestCount in 1..MAX_REQUEST_COUNT) {
            "Test specification max request count must be between 1 and $MAX_REQUEST_COUNT"
        }
        require(profile.maxTrials in 1..MAX_TRIALS) {
            "Test specification max trials must be between 1 and $MAX_TRIALS"
        }
        require(profile.allowedCalls.size <= MAX_ALLOWED_CALLS) { "Test specification has too many allowed calls" }
        require(profile.observationSources.size <= MAX_OBSERVATION_SOURCES) {
            "Test specification has too many observation sources"
        }
        if (profile.executionEnabled) {
            require(profile.allowedCalls.isNotEmpty()) { "Test specification execution needs an allowed call" }
        }
        if (profile.stateChangingAllowed) {
            require(target.environment in WRITABLE_ENVIRONMENTS) {
                "Test specification state changes are refused in '${target.environment}'"
            }
            require(profile.reset?.method == CleanupMethod.ENVIRONMENT_RESET) {
                "State-changing test specifications require a verified environment reset"
            }
        }

        profile.authProfiles.forEach { name ->
            require(IDENTIFIER_PATTERN.matches(name)) { "Test specification auth profile '$name' is invalid" }
        }
        val duplicateCalls = profile.allowedCalls.groupingBy { call -> call.key() }
            .eachCount().filterValues { it > 1 }.keys
        require(duplicateCalls.isEmpty()) {
            "Test specification has duplicate allowed calls: ${duplicateCalls.sorted().joinToString()}"
        }
        profile.allowedCalls.forEach { call -> call.validate(profile.authProfiles, "allowed call") }

        val duplicateSources = profile.observationSources.groupingBy { source -> source.name }
            .eachCount().filterValues { it > 1 }.keys
        require(duplicateSources.isEmpty()) {
            "Test specification has duplicate observation sources: ${duplicateSources.sorted().joinToString()}"
        }
        profile.observationSources.forEach { source ->
            require(IDENTIFIER_PATTERN.matches(source.name)) { "Observation source '${source.name}' is invalid" }
            require(source.fields.isNotEmpty() && source.fields.all(IDENTIFIER_PATTERN::matches)) {
                "Observation source '${source.name}' must declare valid fields"
            }
            source.authProfile?.let { authProfile ->
                require(authProfile in profile.authProfiles) {
                    "Observation source '${source.name}' uses undeclared auth profile '$authProfile'"
                }
            }
            source.validateEndpoint()
        }
        profile.validateFaultInjectionDeclaration()
        profile.reset?.validate(profile.authProfiles)
    }

    private fun TestSpecExecutionProfileDefinition.validateFaultInjectionDeclaration() {
        supportedFaults.forEach { fault ->
            require(UPPER_IDENTIFIER_PATTERN.matches(fault)) { "Supported fault '$fault' is invalid" }
        }
        infrastructureTargets.forEach { targetName ->
            require(TARGET_NAME_PATTERN.matches(targetName)) { "Infrastructure target '$targetName' is invalid" }
        }
        require(supportedFaults.isEmpty() || faultInjection != null) {
            "Supported faults require a fault-injection endpoint declaration"
        }
        faultInjection?.validate(authProfiles)
    }

    private fun ProfileFaultInjectionDefinition.validate(authProfiles: Set<String>) {
        injectEndpoint.validate(authProfiles, "fault inject endpoint")
        releaseEndpoint.validate(authProfiles, "fault release endpoint")
        require(maxTtl in MIN_FAULT_TTL..MAX_FAULT_TTL) {
            "Fault injection max TTL must be between $MIN_FAULT_TTL and $MAX_FAULT_TTL"
        }
    }

    private fun ProfileResetDefinition.validate(authProfiles: Set<String>) {
        when (method) {
            CleanupMethod.NOT_REQUIRED -> {
                require(hook == null && verifications.isEmpty()) {
                    "A NOT_REQUIRED reset must not declare a hook or verification"
                }
            }
            CleanupMethod.ENVIRONMENT_RESET -> {
                require(hook != null) { "An environment reset must declare a hook" }
                require(expectedDuration in MIN_RESET_DURATION..MAX_RESET_DURATION) {
                    "Reset expected duration must be between $MIN_RESET_DURATION and $MAX_RESET_DURATION"
                }
                require(verifications.isNotEmpty()) { "An environment reset must declare a verification" }
                hook.validate(authProfiles, "reset hook")
                verifications.forEach { verification -> verification.validate(authProfiles) }
            }
        }
    }

    private fun ProfileResetVerificationDefinition.validate(authProfiles: Set<String>) {
        require(IDENTIFIER_PATTERN.matches(id)) { "Reset verification id '$id' is invalid" }
        require(expression.isNotBlank()) { "Reset verification '$id' expression is required" }
        require(condition.isNotBlank()) { "Reset verification '$id' condition is required" }
        require(call.method.uppercase() in READ_METHODS) { "Reset verification '$id' must use a read-only call" }
        call.validate(authProfiles, "reset verification '$id'")
        if (readTiming.rule != StabilityRule.IMMEDIATE) {
            require(!readTiming.maxWait.isZero && !readTiming.maxWait.isNegative) {
                "Reset verification '$id' max wait must be positive"
            }
            require(!readTiming.interval.isZero && !readTiming.interval.isNegative) {
                "Reset verification '$id' interval must be positive"
            }
        }
    }

    private fun ProfileHttpCallDefinition.validate(authProfiles: Set<String>, label: String) {
        require(method.uppercase() in HTTP_METHODS) { "Test specification $label method '$method' is invalid" }
        path.validateTemplatePath("Test specification $label path")
        operationId?.let { id ->
            require(OPENAPI_OPERATION_ID_PATTERN.matches(id)) {
                "Test specification $label operationId '$id' is invalid"
            }
        }
        authProfile?.let { profile ->
            require(profile in authProfiles) { "Test specification $label uses undeclared auth profile '$profile'" }
        }
    }

    private fun ProfileHttpCallDefinition.key(): String = "${method.uppercase()} $path"

    private fun ProfileObservationSourceDefinition.validateEndpoint() {
        when (kind) {
            ProfileObservationSourceKind.HARNESS_STATE -> validateHarnessEndpoint()
            ProfileObservationSourceKind.PROMETHEUS -> validateQueriedEndpoint("Prometheus")
            ProfileObservationSourceKind.TRACE -> {
                validateQueriedEndpoint("Trace")
                validateTraceScoping()
            }
        }
    }

    private fun ProfileObservationSourceDefinition.validateHarnessEndpoint() {
        endpoint.validateTemplatePath("Observation source '$name' endpoint")
        require('{' !in endpoint && '}' !in endpoint) {
            "Observation source '$name' endpoint must not contain placeholders"
        }
        require(queries.isEmpty()) { "HARNESS_STATE source '$name' must not declare Prometheus queries" }
    }

    /**
     * A telemetry store ARL queries directly by absolute address.
     *
     * The query itself is the authority here, so the Profile has to own one per field. Letting a specification
     * supply the query would hand a model an arbitrary read against the metric or trace store, which is exactly
     * the scope the Profile exists to bound. The address still has to pass the Target's CIDR allowlist at
     * connection time; this only refuses an address that could never be checked.
     */
    private fun ProfileObservationSourceDefinition.validateQueriedEndpoint(label: String) {
        val uri = endpoint.validUri("$label source '$name' endpoint")
        require(
            uri.isAbsolute && uri.scheme.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null,
        ) { "$label source '$name' endpoint must be an absolute HTTP URL without credentials or query" }
        require(queries.keys == fields) {
            "$label source '$name' must declare exactly one query for every field"
        }
        require(queries.values.all { query -> query.isNotBlank() && query.length <= MAX_QUERY_LENGTH }) {
            "$label source '$name' queries must contain 1 to $MAX_QUERY_LENGTH characters"
        }
    }

    /**
     * A trace query has to name the trial it is asking about.
     *
     * Without it the query also matches another developer's request, an earlier trial, and this trial's own setup
     * work - and a trace carrying one half of a pair only because it belongs to someone else is reported as a
     * violation nobody committed. The Profile is where this is enforced because the Profile is what a human
     * approved: a specification cannot add the scope, and must not be able to leave it out.
     */
    private fun ProfileObservationSourceDefinition.validateTraceScoping() {
        val unscoped = queries.filterValues { query -> TraceScope.PLACEHOLDER !in query }.keys
        require(unscoped.isEmpty()) {
            "Trace source '$name' queries must contain ${TraceScope.PLACEHOLDER} so spans can be attributed to " +
                "one trial; missing in ${unscoped.sorted()}"
        }
    }

    private fun String.validateTemplatePath(label: String) {
        require(length <= MAX_PATH_LENGTH) { "$label exceeds $MAX_PATH_LENGTH characters" }
        val resolved = DOUBLE_PLACEHOLDER.replace(this, "value").let { SINGLE_PLACEHOLDER.replace(it, "value") }
        val uri = resolved.validUri(label)
        require(
            !uri.isAbsolute && uri.host == null && uri.userInfo == null && uri.query == null && uri.fragment == null,
        ) { "$label must be a relative HTTP path without query, fragment, or user info" }
        require(uri.rawPath?.startsWith('/') == true && !uri.rawPath.startsWith("//")) {
            "$label must start with one slash"
        }
        require(uri.path.split('/').none { segment -> segment == ".." }) { "$label must not contain path traversal" }
    }

    private fun String.validUri(label: String): URI = try {
        URI(this)
    } catch (exception: URISyntaxException) {
        throw IllegalArgumentException("$label is not a valid URI", exception)
    }

    private companion object {
        const val MAX_ALLOWED_CALLS = 100
        const val MAX_CONCURRENCY = 1_000
        const val MAX_OBSERVATION_SOURCES = 20
        const val MAX_PATH_LENGTH = 1_000
        const val MAX_QUERY_LENGTH = 4_000
        const val MAX_REQUEST_COUNT = 100_000
        const val MAX_TRIALS = 1_000
        val DOUBLE_PLACEHOLDER = Regex("\\{\\{[A-Za-z0-9_.-]+}}")
        val SINGLE_PLACEHOLDER = Regex("\\{[A-Za-z0-9_.-]+}")
        val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        val HTTP_SCHEMES = setOf("http", "https")
        val IDENTIFIER_PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]{0,99}")
        val OPENAPI_OPERATION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        val UPPER_IDENTIFIER_PATTERN = Regex("[A-Z][A-Z0-9_]{1,99}")
        val TARGET_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")
        val READ_METHODS = setOf("GET", "HEAD")
        val WRITABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
        val MIN_RESET_DURATION: Duration = Duration.ofMillis(100)
        val MAX_RESET_DURATION: Duration = Duration.ofMinutes(30)
        val MIN_FAULT_TTL: Duration = Duration.ofSeconds(1)
        val MAX_FAULT_TTL: Duration = Duration.ofMinutes(30)
    }
}
