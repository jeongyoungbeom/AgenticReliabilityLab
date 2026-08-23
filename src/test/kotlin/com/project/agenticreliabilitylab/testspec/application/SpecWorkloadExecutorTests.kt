package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.ExecutionPolicy
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionPlan
import com.project.agenticreliabilitylab.testspec.domain.SetupStep
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The executor decides how many requests go out and when, and nothing else.
 *
 * The tests that matter here are the ones about honesty: a trial that could not be carried out has to say so
 * rather than hand back a partial result that reads like a clean run.
 */
class SpecWorkloadExecutorTests {
    private val mapper = ObjectMapper()
    private val references = SpecReferenceResolver(mapper)
    private val evaluator = ResponsePathEvaluator(mapper)
    private val clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    @Test
    fun `carries a value captured during setup into the workload`() {
        val transport = RecordingTransport { request ->
            if (request.uri.path == "/products") jsonResponse(201, """{"id":"p-9"}""") else jsonResponse(201, "{}")
        }

        val execution = executor(transport).execute(specification(), testTarget(), "run-1", 1)

        assertTrue(execution.completed)
        val orders = transport.requests.filter { it.uri.path.startsWith("/orders") }
        assertEquals(2, orders.size)
        assertTrue(orders.all { it.body.contains("\"productId\":\"p-9\"") })
        assertEquals("p-9", execution.bindings["setup.product.productId"])
    }

    @Test
    fun `numbers each request so an idempotency key can differ per request`() {
        val transport = RecordingTransport { request ->
            if (request.uri.path == "/products") jsonResponse(201, """{"id":"p-9"}""") else jsonResponse(201, "{}")
        }

        executor(transport).execute(specification(), testTarget(), "run-1", 3)

        val keys = transport.requests
            .filter { it.uri.path.startsWith("/orders") }
            .map { it.headers.getValue("Idempotency-Key") }
        assertEquals(listOf("run-1-3-1", "run-1-3-2"), keys.sorted())
    }

    @Test
    fun `sends an auth profile as a header and never as part of the specification`() {
        val transport = RecordingTransport { jsonResponse(201, """{"id":"p-9"}""") }

        executor(transport).execute(specification(), testTarget(), "run-1", 1)

        val setupRequest = transport.requests.first()
        assertEquals("Bearer seller-token", setupRequest.headers["Authorization"])
        assertFalse(setupRequest.body.contains("seller-token"))
    }

    @Test
    fun `holds requests at a gate so a concurrency limit is what actually happens`() {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val transport = RecordingTransport { request ->
            if (request.uri.path == "/products") return@RecordingTransport jsonResponse(201, """{"id":"p-9"}""")
            peak.accumulateAndGet(inFlight.incrementAndGet()) { seen, next -> maxOf(seen, next) }
            Thread.sleep(BUSY_MILLIS)
            inFlight.decrementAndGet()
            jsonResponse(201, "{}")
        }

        executor(transport).execute(specification(requestCount = 6, concurrency = 3), testTarget(), "run-1", 1)

        assertEquals(3, peak.get())
    }

    @Test
    fun `stops the trial when the fixture could not be created`() {
        val transport = RecordingTransport { request ->
            if (request.uri.path == "/products") jsonResponse(500, """{"error":"boom"}""") else jsonResponse(201, "{}")
        }

        val execution = executor(transport).execute(specification(), testTarget(), "run-1", 1)

        assertFalse(execution.completed)
        assertTrue(execution.failure!!.contains("product"))
        assertTrue(transport.requests.none { it.uri.path.startsWith("/orders") })
    }

    @Test
    fun `remembers that state may have changed even when the trial failed`() {
        val transport = RecordingTransport { request ->
            if (request.uri.path == "/products") jsonResponse(201, """{"id":"p-9"}""") else error("target is down")
        }

        val execution = executor(transport).execute(specification(), testTarget(), "run-1", 1)

        assertFalse(execution.completed)
        assertTrue(execution.stateChanged)
    }

    @Test
    fun `cancels and joins remaining calls before a failed trial returns`() {
        val secondStarted = CountDownLatch(1)
        val interrupted = AtomicInteger()
        val transport = RecordingTransport { request ->
            if (request.uri.path == "/products") return@RecordingTransport jsonResponse(201, """{"id":"p-9"}""")
            if (request.headers.getValue("Idempotency-Key").endsWith("-1")) {
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
                error("first request failed")
            }
            secondStarted.countDown()
            try {
                Thread.sleep(LONG_RUNNING_MILLIS)
                jsonResponse(201, "{}")
            } catch (exception: InterruptedException) {
                interrupted.incrementAndGet()
                throw exception
            }
        }

        val execution = executor(transport).execute(specification(), testTarget(), "run-1", 1)

        assertFalse(execution.completed)
        assertEquals(1, interrupted.get())
    }

    @Test
    fun `refuses to run a step kind this build still cannot execute`() {
        // Phase 21 made INJECT_FAULT/RELEASE_FAULT executable (see the tests below), so this "unsupported kind"
        // assertion moves to INFRA_ACTION, which stays deferred - infrastructure control is a separate,
        // higher-risk decision the user chose not to build this phase.
        val transport = RecordingTransport { jsonResponse(201, """{"id":"p-9"}""") }
        val faulted = specification().let { spec ->
            spec.copy(
                workload = spec.workload + WorkloadStep(
                    kind = WorkloadStepKind.INFRA_ACTION,
                    name = "stop-payment-service",
                    infraAction = "STOP",
                    infraTarget = "payment-service",
                    infraMaxHold = Duration.ofSeconds(30),
                ),
            )
        }

        val execution = executor(transport).execute(faulted, testTarget(), "run-1", 1)

        assertFalse(execution.completed)
        assertTrue(execution.failure!!.contains("INFRA_ACTION"))
    }

    @Test
    fun `injects a fault, captures its handle, and releases it`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/products" -> jsonResponse(201, """{"id":"p-9"}""")
                "/harness/fault" -> jsonResponse(200, """{"faultId":"f-1"}""")
                "/harness/fault/release" -> jsonResponse(200, "{}")
                else -> jsonResponse(201, "{}")
            }
        }
        val faulted = specification().let { spec ->
            spec.copy(
                workload = spec.workload + listOf(
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
        }

        val execution = executor(transport).execute(faulted, testTarget(), "run-1", 1, faultPlan())

        assertTrue(execution.completed)
        assertEquals("f-1", execution.bindings["workload.payment-down.faultId"])
        assertTrue(execution.pendingFaultHandles.isEmpty())
        val faultRequests = transport.requests.filter { it.uri.path.startsWith("/harness/fault") }
        assertEquals(2, faultRequests.size)
    }

    @Test
    fun `reports an injected fault as still pending when the trial never releases it`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/products" -> jsonResponse(201, """{"id":"p-9"}""")
                "/harness/fault" -> jsonResponse(200, """{"faultId":"f-2"}""")
                else -> jsonResponse(201, "{}")
            }
        }
        val faulted = specification().let { spec ->
            spec.copy(
                workload = spec.workload + WorkloadStep(
                    kind = WorkloadStepKind.INJECT_FAULT,
                    name = "payment-down",
                    faultType = "PAYMENT_FAILURE",
                    faultTtl = Duration.ofSeconds(30),
                ),
            )
        }

        val execution = executor(transport).execute(faulted, testTarget(), "run-1", 1, faultPlan())

        assertTrue(execution.completed)
        assertEquals(listOf("f-2"), execution.pendingFaultHandles)
    }

    @Test
    fun `fails the trial when the inject hook does not return a faultId`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/products" -> jsonResponse(201, """{"id":"p-9"}""")
                "/harness/fault" -> jsonResponse(200, "{}")
                else -> jsonResponse(201, "{}")
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

        val execution = executor(transport).execute(faulted, testTarget(), "run-1", 1, faultPlan())

        assertFalse(execution.completed)
        assertTrue(execution.failure!!.contains("faultId"))
    }

    @Test
    fun `records a request that never got an answer instead of dropping it`() {
        val answered = AtomicInteger()
        val transport = RecordingTransport { request ->
            when {
                request.uri.path == "/products" -> jsonResponse(201, """{"id":"p-9"}""")
                answered.incrementAndGet() == 1 -> throw TargetReadTransportException("connection reset")
                else -> jsonResponse(201, "{}")
            }
        }

        val execution = executor(transport).execute(specification(), testTarget(), "run-1", 1)

        val responses = execution.responses.getValue("responses")
        assertEquals(2, responses.size)
        assertEquals(1, responses.count { !it.delivered })
    }

    private fun executor(transport: RecordingTransport): SpecWorkloadExecutor {
        val caller = SpecHttpCaller(
            transport = transport,
            references = references,
            authProvider = StubAuthProvider(mapOf("seller" to mapOf("Authorization" to "Bearer seller-token"))),
            settings = FixedSpecExecutionSettings(),
        )
        return SpecWorkloadExecutor(
            caller = caller,
            references = references,
            evaluator = evaluator,
            faultInjection = FaultInjectionService(caller, evaluator),
            clock = clock,
        )
    }

    private fun faultPlan() = FaultInjectionPlan(
        injectHook = SpecHttpCall("POST", "/harness/fault", null, emptyMap(), null),
        releaseHook = SpecHttpCall("POST", "/harness/fault/release", null, emptyMap(), null),
        maxTtl = Duration.ofMinutes(5),
    )

    private fun specification(requestCount: Int = 2, concurrency: Int = 2) = TestSpecification(
        id = UUID.randomUUID(),
        specKey = "stock-oversell-concurrent",
        version = 1,
        title = "Concurrent orders oversell stock",
        category = SpecCategory.CONCURRENCY,
        risk = SpecRisk.MODERATE,
        source = SpecSource.RULE_GENERATED,
        targetSystemId = "sideproject",
        profileVersionId = UUID.randomUUID(),
        evidence = emptyList(),
        setup = listOf(
            SetupStep(
                name = "product",
                call = SpecHttpCall(
                    method = "POST",
                    path = "/products",
                    authProfile = "seller",
                    headers = emptyMap(),
                    bodyJson = """{"name":"arl-{{runId}}-{{trialNumber}}","stock":10,"price":1000}""",
                ),
                captures = mapOf("productId" to "response.body.id"),
            ),
        ),
        workload = listOf(
            WorkloadStep(
                kind = WorkloadStepKind.CALL,
                name = "orders",
                call = SpecHttpCall(
                    method = "POST",
                    path = "/orders",
                    authProfile = null,
                    headers = mapOf("Idempotency-Key" to "{{runId}}-{{trialNumber}}-{{requestNumber}}"),
                    bodyJson = """{"items":[{"productId":"{{setup.product.productId}}","quantity":1}]}""",
                ),
                requestCount = requestCount,
                concurrency = concurrency,
                captureAs = "responses",
            ),
        ),
        observations = emptyList(),
        invariants = emptyList(),
        policy = ExecutionPolicy(
            trials = 1,
            aggregation = TrialAggregation.ANY_VIOLATION_FAILS,
            stopPolicy = TrialStopPolicy.STOP_ON_FIRST_VIOLATION,
            cleanupTiming = CleanupTiming.AFTER_ALL,
            trialInterval = Duration.ZERO,
        ),
        cleanup = CleanupMethod.ENVIRONMENT_RESET,
    )

    private companion object {
        const val BUSY_MILLIS = 120L
        const val LONG_RUNNING_MILLIS = 5_000L
    }
}
