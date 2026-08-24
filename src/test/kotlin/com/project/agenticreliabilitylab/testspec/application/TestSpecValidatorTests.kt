package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.ExecutionPolicy
import com.project.agenticreliabilitylab.testspec.domain.Invariant
import com.project.agenticreliabilitylab.testspec.domain.InvariantException
import com.project.agenticreliabilitylab.testspec.domain.Observation
import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.SetupStep
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import com.project.agenticreliabilitylab.testspec.domain.UnmetRequirement
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The validator's job is to make a model unable to widen execution scope.
 *
 * Each test names one way a proposal could reach past the Profile, and asserts it is refused before storage.
 */
class TestSpecValidatorTests {
    private val validator = TestSpecValidator(SpecExpressionEnvironment(), SpecReferenceResolver(ObjectMapper()))

    @Test
    fun `accepts a specification that stays inside the profile`() {
        validator.validate(specification(), capabilities())
    }

    @Test
    fun `refuses a call the profile does not register`() {
        val invented = specification(
            workload = listOf(callStep(SpecHttpCall("POST", "/admin/wipe", "buyer", emptyMap(), "{}"))),
        )

        assertRejects(invented, "not registered")
    }

    @Test
    fun `refuses copying a profile placeholder into an executable call path`() {
        val unresolved = specification(
            observations = listOf(
                observation("dbStock").copy(
                    call = SpecHttpCall("GET", "/products/{id}", null, emptyMap(), null),
                ),
            ),
        )

        assertRejects(unresolved, "must use a {{...}} runtime reference")
    }

    @Test
    fun `refuses an auth profile the profile does not declare`() {
        val escalated = specification(
            workload = listOf(callStep(SpecHttpCall("POST", "/orders", "administrator", emptyMap(), "{}"))),
        )

        assertRejects(escalated, "Auth profile")
    }

    @Test
    fun `enforces the auth profile registered for a specific call`() {
        val profile = capabilities().copy(
            authProfilesByCall = mapOf(
                "POST /products" to "seller",
                "POST /orders" to "buyer",
                "GET /products/{id}" to null,
            ),
        )
        val wrongRole = specification(
            workload = listOf(callStep(orderCall().copy(authProfile = "seller"))),
        )

        val failure = assertFailsWith<SpecValidationException> { validator.validate(wrongRole, profile) }

        assertTrue(failure.violations.any { it.contains("must use auth profile 'buyer'") })
    }

    @Test
    fun `refuses credential and runner managed headers in a specification`() {
        val unsafe = specification(
            workload = listOf(
                callStep(orderCall().copy(headers = mapOf("Authorization" to "literal", "Host" to "elsewhere"))),
            ),
        )

        val failure = assertFailsWith<SpecValidationException> { validator.validate(unsafe, capabilities()) }

        assertTrue(failure.violations.any { it.contains("authProfile") })
        assertTrue(failure.violations.any { it.contains("managed by the Runner") })
    }

    /**
     * Trace attribution rests entirely on this header appearing on workload requests and nowhere else. A
     * specification that could set it would decide which spans are judged as its own.
     */
    @Test
    fun `refuses a specification that sets the trial attribution header`() {
        val unsafe = specification(
            workload = listOf(callStep(orderCall().copy(headers = mapOf("X-ARL-Trial" to "someone-else/1")))),
        )

        val failure = assertFailsWith<SpecValidationException> { validator.validate(unsafe, capabilities()) }

        assertTrue(failure.violations.any { it.contains("managed by the Runner") })
    }

    @Test
    fun `refuses an observation source the profile does not declare`() {
        val spec = specification(
            observations = listOf(
                observation("secretValue", ObservationSourceKind.DECLARED_SOURCE, "internalDb", "password"),
            ),
            invariants = listOf(invariant("any", "secretValue == 0")),
        )

        assertRejects(spec, "undeclared source")
    }

    @Test
    fun `refuses a field the declared source does not provide`() {
        val spec = specification(
            observations = listOf(
                observation("hidden", ObservationSourceKind.DECLARED_SOURCE, "harness", "adminToken"),
            ),
            invariants = listOf(invariant("any", "hidden == 0")),
        )

        assertRejects(spec, "does not provide field")
    }

    @Test
    fun `refuses an invariant that names an observation the run will not produce`() {
        val spec = specification(invariants = listOf(invariant("typo", "dbStokc >= 0")))

        assertRejects(spec, "dbStokc")
    }

    @Test
    fun `refuses an exception that cannot be evaluated`() {
        val spec = specification(
            invariants = listOf(
                invariant(
                    "stock-never-negative", "dbStock >= 0",
                    exceptions = listOf(InvariantException("noSuchValue == 1", "made up", null, "someone")),
                ),
            ),
        )

        assertRejects(spec, "Exception on")
    }

    @Test
    fun `refuses an exception that is the literal true`() {
        val spec = specification(
            invariants = listOf(
                invariant(
                    "stock-never-negative", "dbStock >= 0",
                    exceptions = listOf(InvariantException("true", "always allow it", null, "someone")),
                ),
            ),
        )

        assertRejects(spec, "which would nullify the invariant")
    }

    @Test
    fun `refuses an exception that names none of the specification's observations`() {
        val spec = specification(
            invariants = listOf(
                invariant(
                    "stock-never-negative", "dbStock >= 0",
                    exceptions = listOf(InvariantException("1 == 1", "always allow it", null, "someone")),
                ),
            ),
        )

        assertRejects(spec, "references no observed value")
    }

    @Test
    fun `accepts an exception narrowed to a value the specification actually observes`() {
        val spec = specification(
            invariants = listOf(
                invariant(
                    "stock-never-negative", "dbStock >= 0",
                    exceptions = listOf(
                        InvariantException("dbStock == -1", "known reserved-row sentinel", null, "someone"),
                    ),
                ),
            ),
        )

        validator.validate(spec, capabilities())
    }

    @Test
    fun `refuses a requirement that names a missing invariant`() {
        val spec = specification(
            invariants = listOf(invariant("dependent", "dbStock >= 0", requires = "converged")),
        )

        assertRejects(spec, "which is not declared")
    }

    @Test
    fun `refuses a requirement declared after its dependent invariant`() {
        val spec = specification(
            invariants = listOf(
                invariant("dependent", "dbStock >= 0", requires = "converged"),
                invariant("converged", "dbStock >= 0"),
            ),
        )

        assertRejects(spec, "earlier invariant")
    }

    @Test
    fun `refuses a circular invariant requirement`() {
        val spec = specification(
            invariants = listOf(
                invariant("first", "dbStock >= 0", requires = "second"),
                invariant("second", "dbStock >= 0", requires = "first"),
            ),
        )

        assertRejects(spec, "cycle")
    }

    @Test
    fun `refuses concurrency above the profile limit`() {
        val spec = specification(
            workload = listOf(callStep(orderCall(), requestCount = 500, concurrency = 500)),
        )

        assertRejects(spec, "exceeds the allowed")
    }

    @Test
    fun `refuses non-positive workload limits`() {
        val spec = specification(
            workload = listOf(callStep(orderCall(), requestCount = 0, concurrency = 0)),
        )

        assertRejects(spec, "at least 1")
    }

    @Test
    fun `refuses a non-positive trial count`() {
        val spec = specification().let { current ->
            current.copy(policy = current.policy.copy(trials = 0))
        }

        assertRejects(spec, "Trials must be at least 1")
    }

    @Test
    fun `refuses state change in an environment that does not allow it`() {
        val locked = capabilities().copy(stateChangingAllowed = false, environment = "STAGING")

        val failure = assertFailsWith<SpecValidationException> { validator.validate(specification(), locked) }

        assertTrue(failure.violations.any { it.contains("STAGING") }, failure.violations.toString())
    }

    @Test
    fun `refuses a mutating API observation even when its path is registered`() {
        val mutatingObservation = observation("dbStock").copy(call = orderCall())
        val unsafe = specification(observations = listOf(mutatingObservation))

        assertRejects(unsafe, "must use GET or HEAD")
    }

    @Test
    fun `refuses a state changing specification that understates its risk`() {
        val understated = specification().copy(risk = SpecRisk.SAFE)

        assertRejects(understated, "required 'MODERATE' approval")
    }

    @Test
    fun `refuses waits above the runner ceiling`() {
        val excessiveWait = specification(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.WAIT,
                    name = "too-long",
                    wait = Duration.ofMinutes(6),
                ),
            ),
        )

        assertRejects(excessiveWait, "wait exceeds")
    }

    @Test
    fun `refuses a fault without a deadline so nothing can be left injected`() {
        val spec = specification(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.INJECT_FAULT, name = "payment-failure",
                    faultType = "PAYMENT_FAILURE", faultScope = "next-1", faultTtl = null,
                ),
            ),
        )

        assertRejects(spec, "must declare a TTL")
    }

    @Test
    fun `accepts a fault step this build now executes, within the profile's ttl cap`() {
        val spec = specification(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.INJECT_FAULT,
                    name = "payment-failure",
                    faultType = "PAYMENT_FAILURE",
                    faultTtl = Duration.ofSeconds(30),
                ),
            ),
        )

        validator.validate(spec, capabilities())
    }

    @Test
    fun `refuses a fault ttl above the profile's cap`() {
        val spec = specification(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.INJECT_FAULT,
                    name = "payment-failure",
                    faultType = "PAYMENT_FAILURE",
                    faultTtl = Duration.ofSeconds(90),
                ),
            ),
        )

        assertRejects(spec, "TTL exceeds the allowed")
    }

    @Test
    fun `refuses an infrastructure step this build still cannot execute`() {
        val spec = specification(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.INFRA_ACTION,
                    name = "stop-payment-service",
                    infraAction = "STOP",
                    infraTarget = "payment-service",
                    infraMaxHold = Duration.ofSeconds(30),
                ),
            ),
        )

        assertRejects(spec, "cannot execute step kind")
    }

    @Test
    fun `accepts a declared source and field owned by the active profile`() {
        val spec = specification(
            observations = listOf(
                observation("dbStock", ObservationSourceKind.DECLARED_SOURCE, "harness", "dbStock"),
            ),
        )

        validator.validate(spec, capabilities())
    }

    @Test
    fun `requires one read timing for fields from the same harness snapshot`() {
        val settling = ReadTiming(
            StabilityRule.TWO_CONSECUTIVE_EQUAL,
            Duration.ofSeconds(1),
            Duration.ofMillis(100),
            null,
        )
        val spec = specification(
            observations = listOf(
                observation("dbStock", ObservationSourceKind.DECLARED_SOURCE, "harness", "dbStock"),
                observation(
                    "redisHold", ObservationSourceKind.DECLARED_SOURCE, "harness", "redisHoldCount", settling,
                ),
            ),
        )

        assertRejects(spec, "one shared read timing")
    }

    @Test
    fun `requires positive settling wait and interval`() {
        val invalidTiming = ReadTiming(
            StabilityRule.TWO_CONSECUTIVE_EQUAL,
            Duration.ZERO,
            Duration.ZERO,
            null,
        )
        val spec = specification(
            observations = listOf(
                observation(
                    "dbStock", ObservationSourceKind.DECLARED_SOURCE, "harness", "dbStock", invalidTiming,
                ),
            ),
        )

        assertRejects(spec, "must be positive")
    }

    @Test
    fun `reports every problem at once instead of the first`() {
        val spec = specification(
            workload = listOf(callStep(SpecHttpCall("POST", "/admin/wipe", "administrator", emptyMap(), "{}"))),
            invariants = listOf(invariant("typo", "dbStokc >= 0")),
        )

        val failure = assertFailsWith<SpecValidationException> { validator.validate(spec, capabilities()) }

        assertTrue(failure.violations.size >= 3, failure.violations.toString())
    }

    @Test
    fun `accepts a condition that refers to a value known before the run`() {
        val accounted = specification(
            invariants = listOf(
                invariant("all-accounted", "dbStock == {{setup.product.stock}} - {{workload.orders.requestCount}}"),
            ),
        )

        validator.validate(accounted, capabilities())
    }

    @Test
    fun `refuses a condition that refers to a value only a running trial would have`() {
        val perTrial = specification(
            invariants = listOf(invariant("per-trial", "dbStock == {{setup.product.productId}}")),
        )

        assertRejects(perTrial, "not known before the run")
    }

    private fun assertRejects(specification: TestSpecification, fragment: String) {
        val failure = assertFailsWith<SpecValidationException> { validator.validate(specification, capabilities()) }
        assertTrue(
            failure.violations.any { it.contains(fragment) },
            "expected a violation containing '$fragment' but got ${failure.violations}",
        )
    }

    private fun capabilities() = TargetSpecCapabilities(
        targetSystemId = "pilot-target",
        environment = "LOCAL",
        allowedCalls = setOf("POST /products", "POST /orders", "GET /products/{id}"),
        authProfiles = setOf("seller", "buyer"),
        observationSources = mapOf(
            "harness" to DeclaredObservationSource(
                name = "harness",
                kind = DeclaredObservationSourceKind.HARNESS_STATE,
                endpoint = "/harness/state",
                fields = setOf("dbStock", "redisHoldCount"),
                queries = emptyMap(),
                authProfile = null,
            ),
        ),
        supportedFaults = setOf("PAYMENT_FAILURE"),
        infrastructureTargets = setOf("payment-service"),
        maxFaultTtl = Duration.ofSeconds(60),
        maxConcurrency = 50,
        maxRequestCount = 1000,
        maxTrials = 100,
        stateChangingAllowed = true,
    )

    private fun orderCall() = SpecHttpCall("POST", "/orders", "buyer", emptyMap(), """{"items":[]}""")

    private fun callStep(call: SpecHttpCall, requestCount: Int = 10, concurrency: Int = 10) = WorkloadStep(
        kind = WorkloadStepKind.CALL, name = "orders", call = call,
        requestCount = requestCount, concurrency = concurrency, captureAs = "responses",
    )

    private fun observation(
        id: String,
        kind: ObservationSourceKind = ObservationSourceKind.API,
        sourceName: String? = null,
        expression: String = "response.body.stock",
        readTiming: ReadTiming = ReadTiming.IMMEDIATE,
    ) = Observation(
        id = id, label = id, sourceKind = kind, sourceName = sourceName,
        call = if (kind == ObservationSourceKind.API) {
            SpecHttpCall("GET", "/products/{{setup.product.productId}}", null, emptyMap(), null)
        } else {
            null
        },
        expression = expression, readTiming = readTiming,
    )

    private fun invariant(
        id: String,
        condition: String,
        requires: String? = null,
        exceptions: List<InvariantException> = emptyList(),
    ) = Invariant(id, id, condition, requires, UnmetRequirement.NOT_EVALUATED, exceptions, null)

    private fun specification(
        workload: List<WorkloadStep> = listOf(callStep(orderCall())),
        observations: List<Observation> = listOf(observation("dbStock")),
        invariants: List<Invariant> = listOf(invariant("stock-never-negative", "dbStock >= 0")),
    ) = TestSpecification(
        id = UUID.randomUUID(),
        specKey = "stock-oversell-concurrent",
        version = 1,
        title = "동시 주문 시 초과 판매가 발생하는가",
        category = SpecCategory.CONCURRENCY,
        risk = SpecRisk.MODERATE,
        source = SpecSource.MODEL_PROPOSED,
        targetSystemId = "pilot-target",
        profileVersionId = UUID.randomUUID(),
        evidence = emptyList(),
        setup = listOf(
            SetupStep(
                name = "product",
                call = SpecHttpCall("POST", "/products", "seller", emptyMap(), """{"stock":10}"""),
                captures = mapOf("productId" to "response.body.id"),
            ),
        ),
        workload = workload,
        observations = observations,
        invariants = invariants,
        policy = ExecutionPolicy(
            trials = 20,
            aggregation = TrialAggregation.ANY_VIOLATION_FAILS,
            stopPolicy = TrialStopPolicy.STOP_ON_FIRST_VIOLATION,
            cleanupTiming = CleanupTiming.AFTER_ALL,
            trialInterval = Duration.ZERO,
        ),
        cleanup = CleanupMethod.ENVIRONMENT_RESET,
    )
}
