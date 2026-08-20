package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration

/** Validates the Profile-owned authority for declarative specification execution. */
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
        }
        profile.supportedFaults.forEach { fault ->
            require(UPPER_IDENTIFIER_PATTERN.matches(fault)) { "Supported fault '$fault' is invalid" }
        }
        profile.infrastructureTargets.forEach { targetName ->
            require(TARGET_NAME_PATTERN.matches(targetName)) { "Infrastructure target '$targetName' is invalid" }
        }
        profile.reset?.validate(profile.authProfiles)
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
        authProfile?.let { profile ->
            require(profile in authProfiles) { "Test specification $label uses undeclared auth profile '$profile'" }
        }
    }

    private fun ProfileHttpCallDefinition.key(): String = "${method.uppercase()} $path"

    private fun String.validateTemplatePath(label: String) {
        require(length <= MAX_PATH_LENGTH) { "$label exceeds $MAX_PATH_LENGTH characters" }
        val resolved = DOUBLE_PLACEHOLDER.replace(this, "value").let { SINGLE_PLACEHOLDER.replace(it, "value") }
        val uri = URI(resolved)
        require(
            !uri.isAbsolute && uri.host == null && uri.userInfo == null && uri.query == null && uri.fragment == null,
        ) { "$label must be a relative HTTP path without query, fragment, or user info" }
        require(uri.rawPath?.startsWith('/') == true && !uri.rawPath.startsWith("//")) {
            "$label must start with one slash"
        }
        require(uri.path.split('/').none { segment -> segment == ".." }) { "$label must not contain path traversal" }
    }

    private companion object {
        const val MAX_ALLOWED_CALLS = 100
        const val MAX_CONCURRENCY = 1_000
        const val MAX_OBSERVATION_SOURCES = 20
        const val MAX_PATH_LENGTH = 1_000
        const val MAX_REQUEST_COUNT = 100_000
        const val MAX_TRIALS = 1_000
        val DOUBLE_PLACEHOLDER = Regex("\\{\\{[A-Za-z0-9_.-]+}}")
        val SINGLE_PLACEHOLDER = Regex("\\{[A-Za-z0-9_.-]+}")
        val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        val IDENTIFIER_PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]{0,99}")
        val UPPER_IDENTIFIER_PATTERN = Regex("[A-Z][A-Z0-9_]{1,99}")
        val TARGET_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")
        val READ_METHODS = setOf("GET", "HEAD")
        val WRITABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
        val MIN_RESET_DURATION: Duration = Duration.ofMillis(100)
        val MAX_RESET_DURATION: Duration = Duration.ofMinutes(30)
    }
}
