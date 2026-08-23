package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyScenarioProfile
import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetprofile.application.TestSpecExecutionProfileValidator
import com.project.agenticreliabilitylab.targetprofile.domain.ExperimentProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileFaultInjectionDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
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
import kotlin.test.assertNull
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
        assertEquals(
            DeclaredObservationSourceKind.HARNESS_STATE,
            mapped.capabilities.observationSources.getValue("harness").kind,
        )
        assertEquals("/harness/reset", mapped.resetPlan.hook?.path)
        assertEquals("orderCount", mapped.resetPlan.verifications.single().id)
    }

    @Test
    fun `maps fault injection endpoints and the max ttl cap`() {
        val withFaults = executionProfile().copy(
            supportedFaults = setOf("PAYMENT_FAILURE"),
            faultInjection = ProfileFaultInjectionDefinition(
                injectEndpoint = ProfileHttpCallDefinition("POST", "/harness/fault"),
                releaseEndpoint = ProfileHttpCallDefinition("POST", "/harness/fault/release"),
                maxTtl = Duration.ofMinutes(2),
            ),
        )

        val mapped = mapper.map(version(profile = withFaults))

        assertEquals(Duration.ofMinutes(2), mapped.capabilities.maxFaultTtl)
        assertEquals("/harness/fault", mapped.faultInjectionPlan?.injectHook?.path)
        assertEquals("/harness/fault/release", mapped.faultInjectionPlan?.releaseHook?.path)
    }

    @Test
    fun `defaults the fault ttl cap to zero when the profile declares no fault injection`() {
        val mapped = mapper.map(version(profile = executionProfile()))

        assertEquals(Duration.ZERO, mapped.capabilities.maxFaultTtl)
        assertNull(mapped.faultInjectionPlan)
    }

    @Test
    fun `refuses supported faults without a fault injection declaration`() {
        val unsafe = executionProfile().copy(supportedFaults = setOf("PAYMENT_FAILURE"), faultInjection = null)

        assertFailsWith<IllegalArgumentException> { validator.validate(unsafe, target()) }
    }

    @Test
    fun `refuses a fault injection max ttl outside the allowed bounds`() {
        val unsafe = executionProfile().copy(
            supportedFaults = setOf("PAYMENT_FAILURE"),
            faultInjection = ProfileFaultInjectionDefinition(
                injectEndpoint = ProfileHttpCallDefinition("POST", "/harness/fault"),
                releaseEndpoint = ProfileHttpCallDefinition("POST", "/harness/fault/release"),
                maxTtl = Duration.ofHours(1),
            ),
        )

        assertFailsWith<IllegalArgumentException> { validator.validate(unsafe, target()) }
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

    /**
     * A trace query that names no trial also matches another developer's request and this trial's own setup work.
     * The Profile is where this is caught because the Profile is what a human approved - a specification cannot
     * add the scope, so it must not be able to leave it out either.
     */
    @Test
    fun `refuses a trace source whose query cannot be attributed to one trial`() {
        val unscoped = executionProfile().copy(
            observationSources = listOf(traceSource("""{name="inventory.reserve"}""")),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            validator.validate(unscoped, target())
        }

        assertTrue(failure.message.orEmpty().contains("reserveSpans"))
    }

    @Test
    fun `accepts a trace source whose query names the trial`() {
        val scoped = executionProfile().copy(
            observationSources = listOf(
                traceSource("""{name="inventory.reserve" && span.arl.trial="${'$'}{trial}"}"""),
            ),
        )

        validator.validate(scoped, target())
    }

    private fun traceSource(query: String) = ProfileObservationSourceDefinition(
        name = "traces",
        kind = ProfileObservationSourceKind.TRACE,
        endpoint = "http://127.0.0.1:3200",
        fields = setOf("reserveSpans"),
        queries = mapOf("reserveSpans" to query),
    )

    private fun executionProfile() = TestSpecExecutionProfileDefinition(
        executionEnabled = true,
        allowedCalls = listOf(ProfileHttpCallDefinition("POST", "/products", "seller")),
        authProfiles = setOf("seller"),
        observationSources = listOf(
            ProfileObservationSourceDefinition(
                name = "harness",
                kind = ProfileObservationSourceKind.HARNESS_STATE,
                endpoint = "/harness/state",
                fields = setOf("dbStock"),
            ),
        ),
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
