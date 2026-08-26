package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.ExecutionPolicy
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionPlan
import com.project.agenticreliabilitylab.testspec.domain.FaultAuditAction
import com.project.agenticreliabilitylab.testspec.domain.Invariant
import com.project.agenticreliabilitylab.testspec.domain.Observation
import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.ResetPlan
import com.project.agenticreliabilitylab.testspec.domain.ResetVerification
import com.project.agenticreliabilitylab.testspec.domain.SetupStep
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import com.project.agenticreliabilitylab.testspec.domain.UnmetRequirement
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins down the Runner boundaries where a partial run could otherwise be mistaken for a safe one. */
class TestSpecRunnerTests {
    private val mapper = ObjectMapper()
    private val references = SpecReferenceResolver(mapper)
    private val paths = ResponsePathEvaluator(mapper)
    private val expressions = SpecExpressionEnvironment()

    @Test
    fun `stops on the first violation and reports the trials that actually ran`() {
        val reads = AtomicInteger()
        val transport = RecordingTransport { request ->
            check(request.uri.path == "/probe")
            val value = if (reads.incrementAndGet() == 1) 1 else -1
            jsonResponse(200, """{"value":$value}""")
        }
        val specification = specification(
            trials = 5,
            stopPolicy = TrialStopPolicy.STOP_ON_FIRST_VIOLATION,
            observations = listOf(valueObservation()),
            invariants = listOf(valueInvariant()),
        )

        val outcome = runner(transport).run(specification, testTarget(), ResetPlan.NOT_REQUIRED, "run-stop")

        assertEquals(TrialOutcome.VIOLATED, outcome.result.outcome)
        assertEquals(2, outcome.executions.size)
        assertEquals(2, outcome.result.trialsRun)
        assertEquals(1, outcome.result.trialsViolated)
        assertEquals(2, reads.get())
    }

    @Test
    fun `cleans up in finally when the runner is interrupted after changing state`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/mutate" -> {
                    Thread.currentThread().interrupt()
                    jsonResponse(201, "{}")
                }
                "/harness/reset" -> jsonResponse(200, "{}")
                "/harness/state" -> jsonResponse(200, """{"orderCount":0}""")
                else -> error("Unexpected request to ${request.uri.path}")
            }
        }
        val specification = specification(
            trials = 2,
            trialInterval = Duration.ofMillis(1),
            setup = listOf(mutationStep()),
            cleanup = CleanupMethod.ENVIRONMENT_RESET,
        )

        assertFailsWith<InterruptedException> {
            runner(transport).run(specification, testTarget(), resetPlan(), "run-interrupted")
        }

        assertEquals(
            listOf("/harness/reset", "/harness/state", "/mutate", "/harness/reset", "/harness/state"),
            transport.requests.map { it.uri.path },
        )
    }

    @Test
    fun `blocks the next trial when cleanup verification fails`() {
        val mutations = AtomicInteger()
        val stateReads = AtomicInteger()
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/mutate" -> {
                    mutations.incrementAndGet()
                    jsonResponse(201, "{}")
                }
                "/harness/reset" -> jsonResponse(200, "{}")
                "/harness/state" -> jsonResponse(
                    200,
                    if (stateReads.incrementAndGet() == 1) """{"orderCount":0}""" else """{"orderCount":4}""",
                )
                else -> error("Unexpected request to ${request.uri.path}")
            }
        }
        val specification = specification(
            trials = 3,
            cleanupTiming = CleanupTiming.EACH_TRIAL,
            setup = listOf(mutationStep()),
            cleanup = CleanupMethod.ENVIRONMENT_RESET,
        )

        val outcome = runner(transport).run(specification, testTarget(), resetPlan(), "run-dirty")

        assertEquals(1, mutations.get())
        assertEquals(1, outcome.executions.size)
        assertTrue(outcome.resets.isNotEmpty())
        assertTrue(outcome.resets.first().verified)
        assertFalse(outcome.resets.last().verified)
        assertFalse(outcome.cleanupVerified)
    }

    @Test
    fun `does not send a workload when the pre-run reset cannot be verified`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/mutate" -> error("The workload must not start after an unverified baseline")
                "/harness/reset" -> jsonResponse(200, "{}")
                "/harness/state" -> jsonResponse(200, """{"orderCount":3}""")
                else -> error("Unexpected request to ${request.uri.path}")
            }
        }

        val outcome = runner(transport).run(
            specification(setup = listOf(mutationStep()), cleanup = CleanupMethod.ENVIRONMENT_RESET),
            testTarget(), resetPlan(), "run-pre-reset-failed",
        )

        assertEquals(TrialOutcome.INCONCLUSIVE, outcome.result.outcome)
        assertTrue(outcome.executions.isEmpty().not())
        assertFalse(outcome.cleanupVerified)
        assertEquals(listOf("/harness/reset", "/harness/state"), transport.requests.map { it.uri.path })
    }

    @Test
    fun `blocks the next run when a trial leaves an injected fault unreleased`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/harness/fault" -> jsonResponse(200, """{"faultId":"f-1"}""")
                "/harness/fault/release" -> jsonResponse(500, "{}")
                else -> error("Unexpected request to ${request.uri.path}")
            }
        }
        val faulted = specification().copy(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.INJECT_FAULT,
                    name = "payment-down",
                    faultType = "PAYMENT_FAILURE",
                    faultTtl = Duration.ofSeconds(30),
                ),
            ),
        )

        val outcome = runner(transport).run(
            faulted, testTarget(), ResetPlan.NOT_REQUIRED, "run-fault-stuck",
            faultInjectionPlan = faultPlan(),
        )

        assertFalse(outcome.cleanupVerified)
        assertEquals(
            listOf(FaultAuditAction.INJECTED, FaultAuditAction.RELEASE_FAILED),
            outcome.executions.single().faultEvents.map { it.action },
        )
    }

    @Test
    fun `does not block the next run when the trial itself releases the fault it injected`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/harness/fault" -> jsonResponse(200, """{"faultId":"f-1"}""")
                "/harness/fault/release" -> jsonResponse(200, "{}")
                else -> error("Unexpected request to ${request.uri.path}")
            }
        }
        val faulted = specification().copy(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.INJECT_FAULT,
                    name = "payment-down",
                    faultType = "PAYMENT_FAILURE",
                    faultTtl = Duration.ofSeconds(30),
                ),
                WorkloadStep(
                    kind = WorkloadStepKind.RELEASE_FAULT,
                    name = "release-payment",
                    handleReference = "{{workload.payment-down.faultId}}",
                ),
            ),
        )

        val outcome = runner(transport).run(
            faulted, testTarget(), ResetPlan.NOT_REQUIRED, "run-fault-clean",
            faultInjectionPlan = faultPlan(),
        )

        assertTrue(outcome.cleanupVerified)
    }

    @Test
    fun `refuses a production write before sending any request`() {
        val transport = RecordingTransport { error("A production request must never be sent") }
        val production = testTarget().copy(environment = TargetEnvironment.PRODUCTION)
        val specification = specification(
            setup = listOf(mutationStep()),
            cleanup = CleanupMethod.ENVIRONMENT_RESET,
        )

        val failure = assertFailsWith<SpecExecutionException> {
            runner(transport).run(specification, production, resetPlan(), "run-production")
        }

        assertTrue(failure.message.orEmpty().contains("PRODUCTION"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `refuses a production write hidden in an API observation`() {
        val transport = RecordingTransport { error("A production observation must never mutate the Target") }
        val production = testTarget().copy(environment = TargetEnvironment.PRODUCTION)
        val mutatingObservation = valueObservation().copy(
            call = SpecHttpCall("POST", "/mutate", null, emptyMap(), null),
        )

        assertFailsWith<SpecExecutionException> {
            runner(transport).run(
                specification(observations = listOf(mutatingObservation)),
                production,
                ResetPlan.NOT_REQUIRED,
                "run-production-observation",
            )
        }

        assertTrue(transport.requests.isEmpty())
    }

    private fun runner(transport: RecordingTransport): TestSpecRunner {
        val caller = SpecHttpCaller(
            transport = transport,
            references = references,
            authProvider = StubAuthProvider(emptyMap()),
            settings = FixedSpecExecutionSettings(),
        )
        val values = SpecValueReader(caller, paths, FixedSpecExecutionSettings())
        val faultInjection = FaultInjectionService(caller, paths)
        return TestSpecRunner(
            executor = SpecWorkloadExecutor(
                caller = caller,
                references = references,
                evaluator = paths,
                faultInjection = faultInjection,
                clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            ),
            observations = SpecObservationReader(
                values,
                paths,
                StubDeclaredObservationSourceClient(),
                FixedSpecExecutionSettings(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            ),
            evaluator = InvariantEvaluator(expressions, references),
            reset = EnvironmentResetService(caller, values, expressions),
            faultInjection = faultInjection,
        )
    }

    private fun faultPlan() = FaultInjectionPlan(
        injectHook = SpecHttpCall("POST", "/harness/fault", null, emptyMap(), null),
        releaseHook = SpecHttpCall("POST", "/harness/fault/release", null, emptyMap(), null),
        maxTtl = Duration.ofMinutes(5),
    )

    private fun specification(
        trials: Int = 1,
        stopPolicy: TrialStopPolicy = TrialStopPolicy.RUN_ALL,
        cleanupTiming: CleanupTiming = CleanupTiming.AFTER_ALL,
        trialInterval: Duration = Duration.ZERO,
        setup: List<SetupStep> = emptyList(),
        observations: List<Observation> = emptyList(),
        invariants: List<Invariant> = emptyList(),
        cleanup: CleanupMethod = CleanupMethod.NOT_REQUIRED,
    ) = TestSpecification(
        id = UUID.randomUUID(),
        specKey = "runner-spec",
        version = 1,
        title = "Runner safety specification",
        category = SpecCategory.CONCURRENCY,
        risk = SpecRisk.MODERATE,
        source = SpecSource.RULE_GENERATED,
        targetSystemId = "sideproject",
        profileVersionId = UUID.randomUUID(),
        evidence = emptyList(),
        setup = setup,
        workload = emptyList(),
        observations = observations,
        invariants = invariants,
        policy = ExecutionPolicy(
            trials = trials,
            aggregation = TrialAggregation.ANY_VIOLATION_FAILS,
            stopPolicy = stopPolicy,
            cleanupTiming = cleanupTiming,
            trialInterval = trialInterval,
        ),
        cleanup = cleanup,
    )

    private fun mutationStep() = SetupStep(
        name = "mutation",
        call = SpecHttpCall("POST", "/mutate", null, emptyMap(), null),
        captures = emptyMap(),
    )

    private fun valueObservation() = Observation(
        id = "value",
        label = "Observed value",
        sourceKind = ObservationSourceKind.API,
        sourceName = null,
        call = SpecHttpCall("GET", "/probe", null, emptyMap(), null),
        expression = "response.body.value",
        readTiming = ReadTiming.IMMEDIATE,
    )

    private fun valueInvariant() = Invariant(
        id = "value-non-negative",
        description = "Value stays non-negative",
        condition = "value >= 0",
        requires = null,
        unmet = UnmetRequirement.NOT_EVALUATED,
        exceptions = emptyList(),
        threshold = null,
    )

    private fun resetPlan() = ResetPlan(
        method = CleanupMethod.ENVIRONMENT_RESET,
        hook = SpecHttpCall("POST", "/harness/reset", null, emptyMap(), null),
        expectedDuration = Duration.ofSeconds(1),
        verifications = listOf(
            ResetVerification(
                id = "orderCount",
                call = SpecHttpCall("GET", "/harness/state", null, emptyMap(), null),
                expression = "response.body.orderCount",
                condition = "orderCount == 0",
                readTiming = ReadTiming.IMMEDIATE,
            ),
        ),
    )
}
