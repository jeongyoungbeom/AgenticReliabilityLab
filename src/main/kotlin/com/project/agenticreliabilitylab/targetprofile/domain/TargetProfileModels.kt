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
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
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
    val testSpecExecution: TestSpecExecutionProfileDefinition? = null,
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
    /** Legacy singular spelling for a relative OpenAPI path ARL may fetch from this registered Target origin. */
    val openApiPath: String? = null,
    /** Explicit relative OpenAPI document paths. This list is an allowlist, never a Swagger UI crawl seed. */
    // Nullable for Profile JSON written before this field existed; [declaredOpenApiPaths] normalizes it at use.
    val openApiPaths: List<String>? = null,
    val sourceRepository: String,
    val identityVerification: IdentityVerificationStatus,
    val capabilities: Set<TargetCapability>,
    val enabled: Boolean,
)

/** The bounded document allowlist, accepting the original singular key for backwards-compatible Profiles. */
fun TargetRegistrationDefinition.declaredOpenApiPaths(): List<String> =
    listOfNotNull(openApiPath) + openApiPaths.orEmpty()

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
    /** Optional Swagger identity when the gateway execution path differs from the service document path. */
    val operationId: String? = null,
)

data class ExperimentProfileDefinition(
    val adapterId: String,
    val executionEnabled: Boolean,
    val hostResourceGroup: String,
    val stockConcurrency: StockConcurrencyScenarioProfile,
)

/** Profile-owned limits and allowlists for Phase 17 declarative specification execution. */
data class TestSpecExecutionProfileDefinition(
    val executionEnabled: Boolean,
    val allowedCalls: List<ProfileHttpCallDefinition>,
    val authProfiles: Set<String>,
    val observationSources: List<ProfileObservationSourceDefinition>,
    val supportedFaults: Set<String>,
    val infrastructureTargets: Set<String>,
    val maxConcurrency: Int,
    val maxRequestCount: Int,
    val maxTrials: Int,
    val stateChangingAllowed: Boolean,
    val reset: ProfileResetDefinition?,
    /** Phase 21: required whenever [supportedFaults] is not empty; null otherwise. */
    val faultInjection: ProfileFaultInjectionDefinition? = null,
)

/** A call template contains no credential value; [authProfile] is resolved only by the Runner. */
data class ProfileHttpCallDefinition(
    val method: String,
    val path: String,
    val authProfile: String? = null,
    /** Optional Swagger identity; execution authority remains the exact method/path above. */
    val operationId: String? = null,
)

enum class ProfileObservationSourceKind {
    HARNESS_STATE,
    PROMETHEUS,
    TRACE,
}

data class ProfileObservationSourceDefinition(
    val name: String,
    val kind: ProfileObservationSourceKind,
    /** Relative Target path for HARNESS_STATE, absolute base URL for PROMETHEUS and TRACE. */
    val endpoint: String,
    val fields: Set<String>,
    /**
     * Profile-owned field to query mapping: PromQL for PROMETHEUS, TraceQL for TRACE.
     *
     * Specifications can name a field, never inject a query. A query is executable authority over the Target's
     * telemetry store, so it stays where a human approved it rather than where a model wrote it.
     */
    val queries: Map<String, String> = emptyMap(),
    val authProfile: String? = null,
)

/** How this Target injects and releases a declared fault. A call template, not a per-fault-type endpoint. */
data class ProfileFaultInjectionDefinition(
    val injectEndpoint: ProfileHttpCallDefinition,
    val releaseEndpoint: ProfileHttpCallDefinition,
    val maxTtl: Duration,
)

data class ProfileResetDefinition(
    val method: CleanupMethod,
    val hook: ProfileHttpCallDefinition?,
    val expectedDuration: Duration,
    val verifications: List<ProfileResetVerificationDefinition>,
)

data class ProfileResetVerificationDefinition(
    val id: String,
    val call: ProfileHttpCallDefinition,
    val expression: String,
    val condition: String,
    val readTiming: ProfileReadTimingDefinition,
)

data class ProfileReadTimingDefinition(
    val rule: StabilityRule,
    val maxWait: Duration,
    val interval: Duration,
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
