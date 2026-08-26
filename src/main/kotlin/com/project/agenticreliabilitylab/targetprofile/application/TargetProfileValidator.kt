package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.targetprofile.domain.ExperimentProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ReadOnlyOperationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration

/** Validates one complete Profile before it can be persisted or made active. */
@Component
class TargetProfileValidator(
    private val testSpecExecutionValidator: TestSpecExecutionProfileValidator,
) {
    fun validate(definition: TargetProfileDefinition) {
        definition.target.validate()
        definition.genericHttp?.validate(definition.target.id)
        definition.experiment?.validate(definition.target.id)
        definition.testSpecExecution?.let { profile ->
            testSpecExecutionValidator.validate(profile, definition.target)
        }
    }

    private fun TargetRegistrationDefinition.validate() {
        require(TARGET_ID_PATTERN.matches(id)) { "Target id '$id' is invalid" }
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) {
            "Target name must contain 1 to $MAX_NAME_LENGTH characters"
        }
        require(ADAPTER_ID_PATTERN.matches(adapterType)) { "Target adapter type '$adapterType' is invalid" }
        require(sourceRepository.isNotBlank() && sourceRepository.length <= MAX_SOURCE_REPOSITORY_LENGTH) {
            "Target source repository must contain 1 to $MAX_SOURCE_REPOSITORY_LENGTH characters"
        }
        require(capabilities.isNotEmpty()) { "Target must declare at least one capability" }
        require(allowedCidrs.isNotEmpty()) { "Target must declare at least one allowed CIDR" }
        require(allowedCidrs.size <= MAX_ALLOWED_CIDRS) { "Target has too many allowed CIDRs" }
        allowedCidrs.forEach { com.project.agenticreliabilitylab.target.domain.NetworkCidr.parse(it) }

        val baseUri = baseUrl.toSafeOrigin("base URL")
        val allowedOriginUri = allowedOrigin.toSafeOrigin("allowed origin")
        require(baseUri.normalizedOrigin() == allowedOriginUri.normalizedOrigin()) {
            "Target base URL origin must exactly match its allowed origin"
        }
        healthPath.validateFixedRelativePath("Target health path")
        declaredOpenApiPaths().takeIf { paths -> paths.isNotEmpty() }?.let { paths ->
            require(environment in PILOT_ENVIRONMENTS) {
                "OpenAPI discovery is refused in '$environment'; only LOCAL or TEST Targets are supported"
            }
            require(paths.size <= MAX_OPENAPI_PATHS) { "Target has too many OpenAPI paths" }
            require(paths.distinct().size == paths.size) { "Target has duplicate OpenAPI paths" }
            paths.forEach { path -> path.validateFixedRelativePath("Target OpenAPI path") }
        }
    }

    private fun GenericHttpProfileDefinition.validate(targetSystemId: String) {
        require(HOST_RESOURCE_GROUP_PATTERN.matches(hostResourceGroup)) {
            "Target Spec '$targetSystemId' host resource group is invalid"
        }
        require(maxBatchSize in 1..MAX_BATCH_SIZE) {
            "Target Spec '$targetSystemId' maxBatchSize must be between 1 and $MAX_BATCH_SIZE"
        }
        require(requestTimeout in MIN_TIMEOUT..MAX_TIMEOUT) {
            "Target Spec '$targetSystemId' requestTimeout must be between $MIN_TIMEOUT and $MAX_TIMEOUT"
        }
        require(readOnlyOperations.size <= MAX_OPERATIONS) { "Target Spec '$targetSystemId' has too many operations" }
        val duplicateIds = readOnlyOperations.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Target Spec '$targetSystemId' has duplicate operation ids: ${duplicateIds.sorted().joinToString()}"
        }
        readOnlyOperations.forEach { it.validate(targetSystemId) }
        require(failureInjectionCandidates.size <= MAX_FAILURE_INJECTION_CANDIDATES) {
            "Target Spec '$targetSystemId' has too many failure injection candidates"
        }
        val duplicateFailureIds = failureInjectionCandidates
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateFailureIds.isEmpty()) {
            "Target Spec '$targetSystemId' has duplicate failure injection candidate ids: " +
                duplicateFailureIds.sorted().joinToString()
        }
    }

    private fun ReadOnlyOperationDefinition.validate(targetSystemId: String) {
        require(OPERATION_ID_PATTERN.matches(id) && id != HEALTH_CANDIDATE_ID) {
            "Target Spec '$targetSystemId' operation id '$id' is invalid or reserved"
        }
        require(title.isNotBlank() && title.length <= MAX_NAME_LENGTH) {
            "Target Spec '$targetSystemId' operation '$id' title must contain 1 to $MAX_NAME_LENGTH characters"
        }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "Target Spec '$targetSystemId' operation '$id' description is too long"
        }
        path.validateFixedRelativePath("Target Spec '$targetSystemId' operation '$id' path")
        operationId?.validateOpenApiOperationId("Target Spec '$targetSystemId' operation '$id' operationId")
        require(expectedStatusCodes.isNotEmpty() && expectedStatusCodes.all { it in HTTP_STATUS_RANGE }) {
            "Target Spec '$targetSystemId' operation '$id' must declare valid expected status codes"
        }
    }

    private fun ExperimentProfileDefinition.validate(targetSystemId: String) {
        require(EXPERIMENT_ADAPTER_ID_PATTERN.matches(adapterId)) {
            "Target experiment profile '$targetSystemId' adapter id is invalid"
        }
        require(HOST_RESOURCE_GROUP_PATTERN.matches(hostResourceGroup)) {
            "Target experiment profile '$targetSystemId' host resource group is invalid"
        }
        stockConcurrency.endpoint.validateFixedRelativePath("STOCK_CONCURRENCY endpoint")
        require(
            stockConcurrency.maxStock > 0 &&
                stockConcurrency.maxRequestCount > 0 &&
                stockConcurrency.maxConcurrency > 0 &&
                stockConcurrency.maxQuantityPerRequest > 0,
        ) { "STOCK_CONCURRENCY limits must be positive" }
        require(!stockConcurrency.executionTimeout.isNegative && !stockConcurrency.executionTimeout.isZero) {
            "STOCK_CONCURRENCY execution timeout must be positive"
        }
    }

    private fun String.toSafeOrigin(label: String): URI {
        val uri = URI(this)
        require(uri.isAbsolute && uri.scheme.lowercase() in SAFE_SCHEMES) {
            "Target $label must use an absolute HTTP(S) URI"
        }
        require(
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                uri.rawPath.isNullOrEmptyOrRoot(),
        ) {
            "Target $label must contain only an HTTP(S) origin"
        }
        return uri
    }

    private fun String?.isNullOrEmptyOrRoot(): Boolean = isNullOrEmpty() || this == "/"

    private fun String.validateFixedRelativePath(label: String) {
        require(length <= MAX_PATH_LENGTH) { "$label exceeds $MAX_PATH_LENGTH characters" }
        val uri = URI(this)
        require(
            !uri.isAbsolute && uri.host == null && uri.userInfo == null && uri.query == null && uri.fragment == null,
        ) {
            "$label must be a fixed relative HTTP path without query, fragment, or user info"
        }
        require(uri.rawPath?.startsWith('/') == true && !uri.rawPath.startsWith("//")) {
            "$label must start with one slash"
        }
        require(uri.path.split('/').none { it == ".." }) { "$label must not contain path traversal" }
    }

    private fun String.validateOpenApiOperationId(label: String) {
        require(OPENAPI_OPERATION_ID_PATTERN.matches(this)) {
            "$label must contain 1 to $MAX_OPENAPI_OPERATION_ID_LENGTH safe characters"
        }
    }

    private fun URI.normalizedOrigin(): String {
        val port = if (port >= 0) port else if (scheme.equals("https", true)) 443 else 80
        return "${scheme.lowercase()}://${host.lowercase()}:$port"
    }

    private companion object {
        const val HEALTH_CANDIDATE_ID = "health-reachability"
        const val MAX_ALLOWED_CIDRS = 16
        const val MAX_BATCH_SIZE = 20
        const val MAX_OPERATIONS = 20
        const val MAX_FAILURE_INJECTION_CANDIDATES = 20
        const val MAX_NAME_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 1_000
        const val MAX_SOURCE_REPOSITORY_LENGTH = 500
        const val MAX_PATH_LENGTH = 1_000
        const val MAX_OPENAPI_PATHS = 8
        const val MAX_OPENAPI_OPERATION_ID_LENGTH = 200
        val HTTP_STATUS_RANGE = 100..599
        val MIN_TIMEOUT: Duration = Duration.ofMillis(100)
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(30)
        val SAFE_SCHEMES = setOf("http", "https")
        val TARGET_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,99}")
        val OPERATION_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,99}")
        val ADAPTER_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]{0,99}")
        val EXPERIMENT_ADAPTER_ID_PATTERN = Regex("[A-Z][A-Z0-9_]{2,99}")
        val HOST_RESOURCE_GROUP_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,119}")
        val OPENAPI_OPERATION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        val PILOT_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
    }
}
