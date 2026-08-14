package com.project.agenticreliabilitylab.targetprofile.domain

import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyScenarioProfile
import com.project.agenticreliabilitylab.experiment.domain.TargetExperimentProfile
import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.NetworkCidr
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidateKind
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TargetProfileSource {
    BOOTSTRAP,
    USER_IMPORT,
}

enum class TargetProfileStatus {
    DRAFT,
    ACTIVE,
    SUPERSEDED,
}

data class TargetProfileDefinition(
    val target: TargetRegistrationDefinition,
    val genericHttp: GenericHttpProfileDefinition? = null,
    val experiment: ExperimentProfileDefinition? = null,
)

data class TargetRegistrationDefinition(
    val id: String,
    val name: String,
    val adapterType: String,
    val environment: TargetEnvironment,
    val baseUrl: String,
    val allowedOrigin: String,
    val allowedCidrs: Set<String>,
    val healthPath: String,
    val sourceRepository: String,
    val identityVerification: IdentityVerificationStatus,
    val capabilities: Set<TargetCapability>,
    val enabled: Boolean,
)

data class GenericHttpProfileDefinition(
    val executionEnabled: Boolean,
    val hostResourceGroup: String,
    val maxBatchSize: Int,
    val requestTimeout: Duration,
    val readOnlyOperations: List<ReadOnlyOperationDefinition>,
    val failureInjectionPlanningEnabled: Boolean,
    val failureInjectionCandidates: List<FailureInjectionCandidate>,
)

data class ReadOnlyOperationDefinition(
    val id: String,
    val title: String,
    val description: String,
    val path: String,
    val expectedStatusCodes: Set<Int>,
)

data class ExperimentProfileDefinition(
    val adapterId: String,
    val executionEnabled: Boolean,
    val hostResourceGroup: String,
    val stockConcurrency: StockConcurrencyScenarioProfile,
)

data class TargetProfileVersion(
    val id: UUID,
    val targetSystemId: String,
    val source: TargetProfileSource,
    val status: TargetProfileStatus,
    val checksum: String,
    val definition: TargetProfileDefinition,
    val createdBy: String,
    val createdAt: Instant,
    val activatedBy: String? = null,
    val activatedAt: Instant? = null,
)

data class TargetProfileAuditEvent(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val eventType: TargetProfileAuditEventType,
    val actor: String,
    val correlationId: String,
    val occurredAt: Instant,
)

enum class TargetProfileAuditEventType {
    IMPORTED,
    ACTIVATED,
}

enum class TargetApprovalAggregateType {
    TARGET_TEST_BATCH,
    FAILURE_INJECTION_PLAN,
}

data class TargetApprovalAuditEvent(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val aggregateType: TargetApprovalAggregateType,
    val aggregateId: UUID,
    val actor: String,
    val correlationId: String,
    val occurredAt: Instant,
)

data class ActiveTargetProfile(
    val version: TargetProfileVersion,
    val target: RegisteredTarget,
)

fun TargetRegistrationDefinition.toRegisteredTarget(createdAt: Instant, updatedAt: Instant): RegisteredTarget =
    RegisteredTarget(
        id = id,
        name = name,
        adapterType = adapterType,
        environment = environment,
        baseUri = URI(baseUrl),
        allowedOrigin = URI(allowedOrigin),
        allowedNetworkCidrs = allowedCidrs.mapTo(linkedSetOf(), NetworkCidr::parse),
        healthPath = healthPath,
        sourceRepository = sourceRepository,
        identityVerification = identityVerification,
        capabilities = capabilities,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun GenericHttpProfileDefinition.candidates(targetSystemId: String, healthPath: String): List<TargetTestCandidate> {
    val healthCandidate = TargetTestCandidate(
        id = HEALTH_CANDIDATE_ID,
        targetSystemId = targetSystemId,
        kind = TargetTestCandidateKind.HEALTH_REACHABILITY,
        title = "Health endpoint reachability",
        description = "Registered Target health endpoint must return an HTTP 2xx status.",
        method = "GET",
        path = healthPath,
        expectedStatusCodes = (200..299).toSet(),
        timeout = requestTimeout,
    )
    return listOf(healthCandidate) + readOnlyOperations.map { operation ->
        TargetTestCandidate(
            id = operation.id,
            targetSystemId = targetSystemId,
            kind = TargetTestCandidateKind.HTTP_STATUS_ASSERTION,
            title = operation.title,
            description = operation.description,
            method = "GET",
            path = operation.path,
            expectedStatusCodes = operation.expectedStatusCodes,
            timeout = requestTimeout,
        )
    }
}

fun ExperimentProfileDefinition.toDomain(targetSystemId: String): TargetExperimentProfile = TargetExperimentProfile(
    targetSystemId = targetSystemId,
    adapterId = adapterId,
    executionEnabled = executionEnabled,
    hostResourceGroup = hostResourceGroup,
    stockConcurrency = stockConcurrency,
)

private const val HEALTH_CANDIDATE_ID = "health-reachability"
