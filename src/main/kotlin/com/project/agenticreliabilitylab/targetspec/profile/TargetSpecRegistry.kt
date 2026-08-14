package com.project.agenticreliabilitylab.targetspec.profile

import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidateKind
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestCatalog
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestExecutionSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("arl.target-specs")
data class TargetSpecProperties(
    val registrations: List<TargetSpecRegistration> = emptyList(),
)

data class TargetSpecRegistration(
    val targetSystemId: String,
    val executionEnabled: Boolean = false,
    val hostResourceGroup: String = targetSystemId,
    val maxBatchSize: Int = 10,
    val requestTimeout: Duration = Duration.ofSeconds(5),
    val readOnlyOperations: List<ReadOnlyHttpOperationSpec> = emptyList(),
    val failureInjectionPlanningEnabled: Boolean = false,
    val failureInjectionCandidates: List<FailureInjectionCandidateSpec> = emptyList(),
)

data class ReadOnlyHttpOperationSpec(
    val id: String,
    val title: String,
    val description: String = "",
    val path: String,
    val expectedStatusCodes: Set<Int> = setOf(200),
)

data class FailureInjectionCandidateSpec(
    val id: String,
    val type: com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionType,
    val risk: com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionRisk,
    val title: String,
    val description: String = "",
    val recoveryExpectation: String,
)

data class TargetSpec(
    val targetSystemId: String,
    val executionEnabled: Boolean,
    val hostResourceGroup: String,
    val maxBatchSize: Int,
    val requestTimeout: Duration,
    val readOnlyOperations: List<ReadOnlyHttpOperationSpec>,
    val failureInjectionPlanningEnabled: Boolean,
    val failureInjectionCandidates: List<FailureInjectionCandidateSpec>,
)

class TargetSpecRegistry(
    properties: TargetSpecProperties,
) : TargetTestCatalog {
    private val specs: Map<String, TargetSpec>

    init {
        val duplicateTargetIds = properties.registrations.groupingBy { it.targetSystemId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateTargetIds.isEmpty()) {
            "Duplicate Target Spec registrations: ${duplicateTargetIds.sorted().joinToString()}"
        }

        specs = properties.registrations.associate { registration ->
            require(TARGET_ID_PATTERN.matches(registration.targetSystemId)) {
                "Target Spec target id '${registration.targetSystemId}' is invalid"
            }
            require(registration.maxBatchSize in 1..MAX_BATCH_SIZE) {
                "Target Spec maxBatchSize must be between 1 and $MAX_BATCH_SIZE"
            }
            require(HOST_RESOURCE_GROUP_PATTERN.matches(registration.hostResourceGroup)) {
                "Target Spec '${registration.targetSystemId}' host resource group is invalid"
            }
            require(registration.requestTimeout in MIN_TIMEOUT..MAX_TIMEOUT) {
                "Target Spec requestTimeout must be between $MIN_TIMEOUT and $MAX_TIMEOUT"
            }

            val duplicateOperationIds = registration.readOnlyOperations.groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            require(duplicateOperationIds.isEmpty()) {
                "Target Spec '${registration.targetSystemId}' has duplicate operation ids: ${duplicateOperationIds.sorted().joinToString()}"
            }
            registration.readOnlyOperations.forEach { operation -> operation.validate(registration.targetSystemId) }
            require(registration.failureInjectionCandidates.size <= MAX_FAILURE_INJECTION_CANDIDATES) {
                "Target Spec '${registration.targetSystemId}' has too many failure injection candidates"
            }
            val duplicateFailureCandidateIds = registration.failureInjectionCandidates.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            require(duplicateFailureCandidateIds.isEmpty()) {
                "Target Spec '${registration.targetSystemId}' has duplicate failure injection candidate ids: ${duplicateFailureCandidateIds.sorted().joinToString()}"
            }
            registration.failureInjectionCandidates.forEach { it.validate(registration.targetSystemId) }

            registration.targetSystemId to TargetSpec(
                targetSystemId = registration.targetSystemId,
                executionEnabled = registration.executionEnabled,
                hostResourceGroup = registration.hostResourceGroup,
                maxBatchSize = registration.maxBatchSize,
                requestTimeout = registration.requestTimeout,
                readOnlyOperations = registration.readOnlyOperations,
                failureInjectionPlanningEnabled = registration.failureInjectionPlanningEnabled,
                failureInjectionCandidates = registration.failureInjectionCandidates,
            )
        }
    }

    fun require(targetSystemId: String): TargetSpec =
        specs[targetSystemId] ?: throw TargetSpecNotFoundException(targetSystemId)

    override fun maxBatchSize(targetSystemId: String): Int = require(targetSystemId).maxBatchSize

    override fun requireGenericExecutionEnabled(targetSystemId: String): TargetTestExecutionSettings {
        val spec = require(targetSystemId)
        require(spec.executionEnabled) {
            "Target Spec '$targetSystemId' does not enable generic HTTP execution"
        }
        return TargetTestExecutionSettings(spec.hostResourceGroup, spec.requestTimeout)
    }

    override fun candidates(targetSystemId: String, healthPath: String): List<TargetTestCandidate> {
        val spec = require(targetSystemId)
        val healthCandidate = TargetTestCandidate(
            id = HEALTH_CANDIDATE_ID,
            targetSystemId = targetSystemId,
            kind = TargetTestCandidateKind.HEALTH_REACHABILITY,
            title = "Health endpoint reachability",
            description = "Registered Target health endpoint must return an HTTP 2xx status.",
            method = "GET",
            path = healthPath.validatePath("registered health path"),
            expectedStatusCodes = (200..299).toSet(),
            timeout = spec.requestTimeout,
        )
        return listOf(healthCandidate) + spec.readOnlyOperations.map { operation ->
            TargetTestCandidate(
                id = operation.id,
                targetSystemId = targetSystemId,
                kind = TargetTestCandidateKind.HTTP_STATUS_ASSERTION,
                title = operation.title,
                description = operation.description,
                method = "GET",
                path = operation.path.validatePath("operation '${operation.id}' path"),
                expectedStatusCodes = operation.expectedStatusCodes,
                timeout = spec.requestTimeout,
            )
        }
    }

    override fun failureInjectionCandidates(targetSystemId: String): List<FailureInjectionCandidate> {
        val spec = require(targetSystemId)
        requireFailureInjectionPlanningEnabled(targetSystemId)
        return spec.failureInjectionCandidates.map { candidate ->
            FailureInjectionCandidate(candidate.id, targetSystemId, candidate.type, candidate.risk, candidate.title, candidate.description, candidate.recoveryExpectation)
        }
    }

    override fun requireFailureInjectionPlanningEnabled(targetSystemId: String) {
        require(require(targetSystemId).failureInjectionPlanningEnabled) {
            "Failure injection planning is disabled for Target '$targetSystemId'"
        }
    }

    private fun ReadOnlyHttpOperationSpec.validate(targetSystemId: String) {
        require(OPERATION_ID_PATTERN.matches(id) && id != HEALTH_CANDIDATE_ID) {
            "Target Spec '$targetSystemId' operation id '$id' is invalid or reserved"
        }
        require(title.isNotBlank() && title.length <= 200) {
            "Target Spec '$targetSystemId' operation '$id' title must contain 1 to 200 characters"
        }
        require(description.length <= 1_000) {
            "Target Spec '$targetSystemId' operation '$id' description must contain at most 1000 characters"
        }
        path.validatePath("Target Spec '$targetSystemId' operation '$id' path")
        require(expectedStatusCodes.isNotEmpty() && expectedStatusCodes.all { it in 100..599 }) {
            "Target Spec '$targetSystemId' operation '$id' must declare valid expectedStatusCodes"
        }
    }

    private fun FailureInjectionCandidateSpec.validate(targetSystemId: String) {
        require(OPERATION_ID_PATTERN.matches(id) && id != HEALTH_CANDIDATE_ID) {
            "Target Spec '$targetSystemId' failure injection candidate id '$id' is invalid or reserved"
        }
        require(title.isNotBlank() && title.length <= 200) { "Failure injection candidate '$id' title must contain 1 to 200 characters" }
        require(description.length <= 1_000) { "Failure injection candidate '$id' description must contain at most 1000 characters" }
        require(recoveryExpectation.isNotBlank() && recoveryExpectation.length <= 1_000) { "Failure injection candidate '$id' recoveryExpectation must contain 1 to 1000 characters" }
    }

    private fun String.validatePath(label: String): String {
        require(length <= MAX_PATH_LENGTH) { "$label exceeds $MAX_PATH_LENGTH characters" }
        val uri = URI(this)
        require(!uri.isAbsolute && uri.host == null && uri.userInfo == null && uri.fragment == null) {
            "$label must be a relative HTTP path"
        }
        require(uri.rawPath?.startsWith('/') == true && !uri.rawPath.startsWith("//")) {
            "$label must start with one slash"
        }
        require(uri.path.split('/').none { it == ".." }) {
            "$label must not contain path traversal"
        }
        return this
    }

    private companion object {
        const val HEALTH_CANDIDATE_ID = "health-reachability"
        const val MAX_BATCH_SIZE = 20
        const val MAX_FAILURE_INJECTION_CANDIDATES = 20
        const val MAX_PATH_LENGTH = 1_000
        val MIN_TIMEOUT: Duration = Duration.ofMillis(100)
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(30)
        val TARGET_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,99}")
        val OPERATION_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,99}")
        val HOST_RESOURCE_GROUP_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,119}")
    }
}

class TargetSpecNotFoundException(targetSystemId: String) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Target Spec", targetSystemId)
