package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetintelligence.application.port.TargetKnowledgeSnapshotStore
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedOperation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeCitation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceDocument
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceType
import com.project.agenticreliabilitylab.targetintelligence.domain.OperationMutability
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeContent
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeSnapshot
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileFaultInjectionDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileReadTimingDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ReadOnlyOperationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class PilotDiscoveryServiceTests {
    @Test
    fun `keeps only allowlisted operations and reports missing workflow operations as not ready`() {
        val version = profileVersion()
        val snapshot = snapshot(version)
        val profiles = Mockito.mock(TargetProfileService::class.java)
        val snapshots = Mockito.mock(TargetKnowledgeSnapshotStore::class.java)
        Mockito.`when`(profiles.findActive(version.targetSystemId)).thenReturn(version)
        Mockito.`when`(snapshots.findByTarget(version.targetSystemId, 50)).thenReturn(listOf(snapshot))

        val discovery = PilotDiscoveryService(profiles, snapshots).find(version.targetSystemId)

        assertEquals(listOf("GET /api/products", "POST /api/products"), discovery.discoveredOperations.map {
            operation -> "${operation.method} ${operation.executionPath}"
        })
        assertEquals(1, discovery.ignoredOperationCount)
        assertEquals(PilotCandidateReadiness.READY, discovery.candidates.single { it.id == "availability" }.readiness)
        assertEquals(PilotCandidateReadiness.READY, discovery.candidates.single { it.id == "product-create" }.readiness)
        assertEquals(
            PilotCandidateReadiness.NOT_READY,
            discovery.candidates.single { it.id == "order-workflow" }.readiness,
        )
        assertEquals(
            PilotCandidateReadiness.NOT_READY,
            discovery.candidates.single { it.id == "payment-success" }.readiness,
        )
    }

    @Test
    fun `blocks every pilot template when formal Harness fault routes are not declared`() {
        val version = profileVersion(includeFaultInjection = false)
        val profiles = Mockito.mock(TargetProfileService::class.java)
        val snapshots = Mockito.mock(TargetKnowledgeSnapshotStore::class.java)
        Mockito.`when`(profiles.findActive(version.targetSystemId)).thenReturn(version)
        Mockito.`when`(snapshots.findByTarget(version.targetSystemId, 50)).thenReturn(listOf(snapshot(version)))

        val candidates = PilotDiscoveryService(profiles, snapshots).find(version.targetSystemId).candidates
        val availability = candidates.single { candidate -> candidate.id == "availability" }

        assertEquals(true, candidates.all { candidate -> candidate.readiness == PilotCandidateReadiness.NOT_READY })
        assertEquals(PilotCandidateReadiness.NOT_READY, availability.readiness)
        assertEquals(true, "Harness POST fault injection" in availability.missingOperations)
        assertEquals(true, "Harness POST fault release" in availability.missingOperations)
    }

    private fun profileVersion(includeFaultInjection: Boolean = true): TargetProfileVersion {
        val target = targetDefinition()
        return TargetProfileVersion(
            id = UUID.randomUUID(),
            targetSystemId = target.id,
            source = TargetProfileSource.USER_IMPORT,
            status = TargetProfileStatus.ACTIVE,
            checksum = "profile-checksum",
            definition = TargetProfileDefinition(
                target,
                genericDefinition(),
                null,
                executionDefinition(includeFaultInjection),
            ),
            createdBy = "tester",
            createdAt = Instant.EPOCH,
            activatedBy = "tester",
            activatedAt = Instant.EPOCH,
        )
    }

    private fun targetDefinition(): TargetRegistrationDefinition =
        TargetRegistrationDefinition(
            id = "sideproject-local",
            name = "SideProject",
            adapterType = "HTTP_TARGET",
            environment = TargetEnvironment.LOCAL,
            baseUrl = "http://127.0.0.1",
            allowedOrigin = "http://127.0.0.1",
            allowedCidrs = setOf("127.0.0.0/8"),
            healthPath = "/actuator/health",
            openApiPath = "/api-docs/product",
            sourceRepository = "sideproject",
            identityVerification = IdentityVerificationStatus.CONFIGURATION_ONLY,
            capabilities = setOf(TargetCapability.HEALTH, TargetCapability.HTTP_API),
            enabled = true,
        )

    private fun genericDefinition(): GenericHttpProfileDefinition =
        GenericHttpProfileDefinition(
            executionEnabled = true,
            hostResourceGroup = "sideproject",
            maxBatchSize = 5,
            requestTimeout = Duration.ofSeconds(5),
            readOnlyOperations = listOf(
                ReadOnlyOperationDefinition(
                    id = "catalog",
                    title = "Catalog",
                    description = "",
                    path = "/api/products",
                    expectedStatusCodes = setOf(200),
                    operationId = "getProducts",
                ),
            ),
            failureInjectionPlanningEnabled = false,
            failureInjectionCandidates = emptyList(),
        )

    private fun executionDefinition(includeFaultInjection: Boolean): TestSpecExecutionProfileDefinition =
        TestSpecExecutionProfileDefinition(
            executionEnabled = true,
            allowedCalls = listOf(
                ProfileHttpCallDefinition("GET", "/actuator/health", "harness", "health"),
                ProfileHttpCallDefinition("POST", "/api/products", "seller", "createProduct_1"),
                ProfileHttpCallDefinition("POST", "/api/orders", "buyer", "orders"),
                ProfileHttpCallDefinition("POST", "/api/payments/webhook", null, "webhook"),
            ),
            authProfiles = setOf("seller", "buyer", "harness"),
            observationSources = listOf(
                ProfileObservationSourceDefinition(
                    name = "harness",
                    kind = ProfileObservationSourceKind.HARNESS_STATE,
                    endpoint = "/api/harness/state",
                    fields = setOf(
                        "productCount",
                        "orderCount",
                        "paymentCount",
                        "failedPaymentCount",
                        "completedPaymentCount",
                        "activeFaultCount",
                    ),
                    authProfile = "harness",
                ),
            ),
            supportedFaults = if (includeFaultInjection) setOf("PAYMENT_FAILURE") else emptySet(),
            infrastructureTargets = emptySet(),
            maxConcurrency = 20,
            maxRequestCount = 100,
            maxTrials = 3,
            stateChangingAllowed = true,
            reset = ProfileResetDefinition(
                method = CleanupMethod.ENVIRONMENT_RESET,
                hook = ProfileHttpCallDefinition("POST", "/api/harness/reset", "harness"),
                expectedDuration = Duration.ofSeconds(5),
                verifications = listOf(
                    ProfileResetVerificationDefinition(
                        id = "harness-clean",
                        call = ProfileHttpCallDefinition("GET", "/api/harness/state", "harness"),
                        expression = "response.body.activeFaultCount",
                        condition = "activeFaultCount == 0",
                        readTiming = ProfileReadTimingDefinition(
                            StabilityRule.IMMEDIATE,
                            Duration.ofSeconds(5),
                            Duration.ofMillis(100),
                        ),
                    ),
                ),
            ),
            faultInjection = if (includeFaultInjection) ProfileFaultInjectionDefinition(
                injectEndpoint = ProfileHttpCallDefinition("POST", "/api/harness/fault", "harness"),
                releaseEndpoint = ProfileHttpCallDefinition("POST", "/api/harness/fault/release", "harness"),
                maxTtl = Duration.ofSeconds(120),
            ) else null,
        )

    private fun snapshot(version: TargetProfileVersion): TargetKnowledgeSnapshot = TargetKnowledgeSnapshot(
        id = UUID.randomUUID(),
        targetSystemId = version.targetSystemId,
        profileVersionId = version.id,
        checksum = "snapshot-checksum",
        extractionVersion = "test",
        content = TargetKnowledgeContent(
            sources = listOf(KnowledgeSourceDocument(KnowledgeSourceType.OPENAPI, 100, "document-checksum")),
            operations = listOf(
                operation("GET", "/products", "getProducts", OperationMutability.READ),
                operation("POST", "/products", "createProduct_1", OperationMutability.WRITE),
                operation("DELETE", "/products/{productId}", "deactivateProduct", OperationMutability.WRITE),
            ),
            workflows = emptyList(),
            domainHypotheses = emptyList(),
            invariants = emptyList(),
            riskSignals = emptyList(),
            warnings = emptyList(),
        ),
        createdBy = "tester",
        createdCorrelationId = "test",
        createdAt = Instant.EPOCH,
    )

    private fun operation(
        method: String,
        path: String,
        operationId: String,
        mutability: OperationMutability,
    ) = ExtractedOperation(
        method = method,
        path = path,
        operationId = operationId,
        summary = operationId,
        requestMediaTypes = emptySet(),
        responseStatusCodes = setOf(200),
        mutability = mutability,
        citation = KnowledgeCitation(KnowledgeSourceType.OPENAPI, "paths.$path", "$method $path"),
    )
}
