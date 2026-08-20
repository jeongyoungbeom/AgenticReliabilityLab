package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyScenarioProfile
import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetprofile.application.TestSpecExecutionProfileValidator
import com.project.agenticreliabilitylab.targetprofile.domain.ExperimentProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileReadTimingDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestSpecExecutionProfileMapperTests {
    private val mapper = TestSpecExecutionProfileMapper()
    private val validator = TestSpecExecutionProfileValidator()

    @Test
    fun `maps the active profile and keeps the smaller target declared limit`() {
        val mapped = mapper.map(version(profile = executionProfile(), experiment = experimentProfile()))

        assertEquals(2, mapped.capabilities.maxConcurrency)
        assertEquals(20, mapped.capabilities.maxRequestCount)
        assertEquals(10, mapped.capabilities.maxTrials)
        assertTrue(mapped.capabilities.allowedCalls.contains("POST /products"))
        assertEquals("seller", mapped.capabilities.authProfilesByCall["POST /products"])
        assertEquals("/harness/reset", mapped.resetPlan.hook?.path)
        assertEquals("orderCount", mapped.resetPlan.verifications.single().id)
    }

    @Test
    fun `refuses state changing authority outside local and test environments`() {
        val target = target().copy(environment = TargetEnvironment.PRODUCTION)

        assertFailsWith<IllegalArgumentException> {
            validator.validate(executionProfile(), target)
        }
    }

    @Test
    fun `refuses state changing authority without a verified reset`() {
        val unsafe = executionProfile().copy(reset = null)

        assertFailsWith<IllegalArgumentException> {
            validator.validate(unsafe, target())
        }
    }

    private fun executionProfile() = TestSpecExecutionProfileDefinition(
        executionEnabled = true,
        allowedCalls = listOf(ProfileHttpCallDefinition("POST", "/products", "seller")),
        authProfiles = setOf("seller"),
        observationSources = emptyList(),
        supportedFaults = emptySet(),
        infrastructureTargets = emptySet(),
        maxConcurrency = 50,
        maxRequestCount = 1_000,
        maxTrials = 10,
        stateChangingAllowed = true,
        reset = ProfileResetDefinition(
            method = CleanupMethod.ENVIRONMENT_RESET,
            hook = ProfileHttpCallDefinition("POST", "/harness/reset"),
            expectedDuration = Duration.ofSeconds(30),
            verifications = listOf(
                ProfileResetVerificationDefinition(
                    id = "orderCount",
                    call = ProfileHttpCallDefinition("GET", "/harness/state"),
                    expression = "response.body.orderCount",
                    condition = "orderCount == 0",
                    readTiming = ProfileReadTimingDefinition(
                        StabilityRule.IMMEDIATE,
                        Duration.ZERO,
                        Duration.ZERO,
                    ),
                ),
            ),
        ),
    )

    private fun version(
        profile: TestSpecExecutionProfileDefinition,
        experiment: ExperimentProfileDefinition? = null,
    ) = TargetProfileVersion(
        id = UUID.randomUUID(),
        targetSystemId = "sideproject",
        source = TargetProfileSource.USER_IMPORT,
        status = TargetProfileStatus.ACTIVE,
        checksum = "checksum",
        definition = TargetProfileDefinition(target(), experiment = experiment, testSpecExecution = profile),
        createdBy = "tester",
        createdAt = Instant.EPOCH,
    )

    private fun target() = TargetRegistrationDefinition(
        id = "sideproject",
        name = "Side Project",
        adapterType = "HTTP_TARGET",
        environment = TargetEnvironment.TEST,
        baseUrl = "http://127.0.0.1:18080",
        allowedOrigin = "http://127.0.0.1:18080",
        allowedCidrs = setOf("127.0.0.0/8"),
        healthPath = "/actuator/health",
        sourceRepository = "sideproject",
        identityVerification = IdentityVerificationStatus.CONFIGURATION_ONLY,
        capabilities = setOf(TargetCapability.HEALTH, TargetCapability.HTTP_API),
        enabled = true,
    )

    private fun experimentProfile() = ExperimentProfileDefinition(
        adapterId = "HTTP_SCENARIO_V1",
        executionEnabled = true,
        hostResourceGroup = "sideproject",
        stockConcurrency = StockConcurrencyScenarioProfile(
            endpoint = "/scenario",
            capabilitiesEndpoint = null,
            maxStock = 10,
            maxRequestCount = 20,
            maxConcurrency = 2,
            maxQuantityPerRequest = 1,
            executionTimeout = Duration.ofMinutes(1),
        ),
    )
}
