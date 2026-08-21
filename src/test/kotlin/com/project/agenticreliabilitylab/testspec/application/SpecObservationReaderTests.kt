package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.ExecutionPolicy
import com.project.agenticreliabilitylab.testspec.domain.Observation
import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.RecordedResponse
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import com.project.agenticreliabilitylab.testspec.domain.StepRole
import com.project.agenticreliabilitylab.testspec.domain.StepTiming
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationRead
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationSourceClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading is where an unobserved value could quietly become a passing verdict, so every test here is about
 * telling "we read zero" apart from "we could not read".
 */
class SpecObservationReaderTests {
    private val mapper = ObjectMapper()
    private val references = SpecReferenceResolver(mapper)
    private val evaluator = ResponsePathEvaluator(mapper)

    @Test
    fun `computes a value from the responses the workload already collected`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }
        val observation = Observation(
            id = "successQuantity",
            label = "성공한 주문 수량",
            sourceKind = ObservationSourceKind.RESPONSES,
            sourceName = null,
            call = null,
            expression = "sum(responses[*].body.items[*].quantity)",
            readTiming = ReadTiming.IMMEDIATE,
        )

        val observed = reader(transport).read(specification(observation), testTarget(), execution(), "run-1")

        assertEquals(3L, observed.getValue("successQuantity").value)
    }

    @Test
    fun `reads a value from the target and reports it as observed`() {
        val transport = RecordingTransport { jsonResponse(200, """{"stock":7}""") }

        val observed = reader(transport).read(
            specification(apiObservation(ReadTiming.IMMEDIATE)),
            testTarget(),
            execution(),
            "run-1",
        )

        assertEquals(7L, observed.getValue("dbStock").value)
        assertEquals("/products/p-9", transport.requests.single().uri.path)
    }

    @Test
    fun `waits for an asynchronously propagated value to settle`() {
        val reads = AtomicInteger()
        val transport = RecordingTransport {
            val stock = if (reads.incrementAndGet() <= 1) 9 else 7
            jsonResponse(200, """{"stock":$stock}""")
        }

        val observed = reader(transport).read(
            specification(apiObservation(twoConsecutive())),
            testTarget(),
            execution(),
            "run-1",
        )

        assertEquals(7L, observed.getValue("dbStock").value)
        assertTrue(transport.requests.size >= 3)
    }

    @Test
    fun `reports a value that never settled as unobserved rather than as its last reading`() {
        val reads = AtomicInteger()
        val transport = RecordingTransport { jsonResponse(200, """{"stock":${reads.incrementAndGet()}}""") }

        val observed = reader(transport).read(
            specification(apiObservation(twoConsecutive())),
            testTarget(),
            execution(),
            "run-1",
        )

        val value = observed.getValue("dbStock")
        assertFalse(value.present)
        assertTrue(value.display.contains("settle"))
    }

    @Test
    fun `reports an unreachable read as unobserved instead of guessing`() {
        val transport = RecordingTransport { jsonResponse(503, "service unavailable") }

        val observed = reader(transport).read(
            specification(apiObservation(ReadTiming.IMMEDIATE)),
            testTarget(),
            execution(),
            "run-1",
        )

        assertFalse(observed.getValue("dbStock").present)
    }

    @Test
    fun `says a declared source is not readable rather than treating it as zero`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }
        val observation = Observation(
            id = "redisHold",
            label = "남은 예약",
            sourceKind = ObservationSourceKind.DECLARED_SOURCE,
            sourceName = "harness",
            call = null,
            expression = "redisHoldCount",
            readTiming = ReadTiming.IMMEDIATE,
        )

        val observed = reader(transport).read(specification(observation), testTarget(), execution(), "run-1")

        val value = observed.getValue("redisHold")
        assertFalse(value.present)
        assertTrue(value.display.contains("harness"))
    }

    @Test
    fun `reads a profile declared harness field without changing the invariant identifier`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }
        val observation = Observation(
            id = "redisHold",
            label = "남은 예약",
            sourceKind = ObservationSourceKind.DECLARED_SOURCE,
            sourceName = "harness",
            call = null,
            expression = "redisHoldCount",
            readTiming = ReadTiming.IMMEDIATE,
        )
        val client = StubDeclaredObservationSourceClient { _, fields, _ ->
            assertEquals(setOf("redisHoldCount"), fields)
            mapOf("redisHoldCount" to DeclaredObservationRead.observed(2L))
        }

        val observed = reader(transport, client).read(
            specification(observation),
            testTarget(),
            execution(),
            "run-1",
            mapOf("harness" to harnessSource()),
        )

        assertEquals(2L, observed.getValue("redisHold").value)
    }

    @Test
    fun `reads all harness fields from one coherent snapshot`() {
        val calls = AtomicInteger()
        val client = StubDeclaredObservationSourceClient { _, fields, _ ->
            calls.incrementAndGet()
            assertEquals(linkedSetOf("dbStock", "redisStock"), fields)
            mapOf(
                "dbStock" to DeclaredObservationRead.observed(10L),
                "redisStock" to DeclaredObservationRead.observed(10L),
            )
        }
        val observations = listOf(
            declaredObservation("databaseStock", "dbStock", ReadTiming.IMMEDIATE),
            declaredObservation("cacheStock", "redisStock", ReadTiming.IMMEDIATE),
        )

        val observed = reader(RecordingTransport { jsonResponse(200, "{}") }, client).read(
            specification(observations), testTarget(), execution(), "run-1", mapOf("harness" to harnessSource()),
        )

        assertEquals(1, calls.get())
        assertEquals(10L, observed.getValue("databaseStock").value)
        assertEquals(10L, observed.getValue("cacheStock").value)
    }

    @Test
    fun `a failed sample breaks consecutive equality`() {
        val calls = AtomicInteger()
        val client = StubDeclaredObservationSourceClient { _, fields, _ ->
            val read = when (calls.incrementAndGet()) {
                1 -> DeclaredObservationRead.observed(7L)
                2 -> DeclaredObservationRead.missing("temporary failure")
                else -> DeclaredObservationRead.observed(7L)
            }
            fields.associateWith { read }
        }
        val observation = declaredObservation("databaseStock", "dbStock", twoConsecutive())

        val observed = reader(RecordingTransport { jsonResponse(200, "{}") }, client).read(
            specification(observation), testTarget(), execution(), "run-1", mapOf("harness" to harnessSource()),
        )

        assertEquals(7L, observed.getValue("databaseStock").value)
        assertTrue(calls.get() >= 4, "a failed sample must reset the consecutive-read count")
    }

    @Test
    fun `caps polling sleep and request budget to the observation deadline`() {
        val budgets = mutableListOf<Duration>()
        val client = StubDeclaredObservationSourceClient { _, fields, timeout ->
            budgets.add(timeout)
            fields.associateWith { DeclaredObservationRead.missing("still unavailable") }
        }
        val readTiming = ReadTiming(
            rule = StabilityRule.TWO_CONSECUTIVE_EQUAL,
            maxWait = Duration.ofSeconds(1),
            interval = Duration.ofSeconds(10),
            evidence = null,
        )
        val started = System.nanoTime()

        val observed = reader(
            RecordingTransport { jsonResponse(200, "{}") },
            client,
            maxObservationWait = Duration.ofMillis(75),
        ).read(
            specification(declaredObservation("databaseStock", "dbStock", readTiming)),
            testTarget(),
            execution(),
            "run-1",
            mapOf("harness" to harnessSource()),
        )
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertFalse(observed.getValue("databaseStock").present)
        assertTrue(budgets.single() <= Duration.ofMillis(75))
        assertTrue(elapsed < Duration.ofSeconds(1), "polling exceeded the configured observation deadline")
    }

    @Test
    fun `bounds a trace read to the window the trial actually ran in`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }
        val client = StubDeclaredObservationSourceClient { _, fields, _ ->
            fields.associateWith { DeclaredObservationRead.observed(emptyList<Map<String, Any>>()) }
        }
        val observation = Observation(
            id = "reserveSpans",
            label = "예약 구간",
            sourceKind = ObservationSourceKind.DECLARED_SOURCE,
            sourceName = "traces",
            call = null,
            expression = "reserveSpans",
            readTiming = ReadTiming.IMMEDIATE,
        )

        val observed = reader(transport, client).read(
            specification(observation),
            testTarget(),
            timedExecution(),
            "run-1",
            mapOf("traces" to traceSource()),
        )

        assertTrue(observed.getValue("reserveSpans").present)
        val window = requireNotNull(client.requests.single().window)
        assertTrue(window.start.isBefore(WORKLOAD_START))
        assertTrue(window.end.isAfter(WORKLOAD_END))
    }

    /**
     * Two fields of one trace source must come from one sampling round.
     *
     * Reading them in separate settling loops means the reservation list and the deduction list describe different
     * moments, and a trace that arrived between the two reads appears in one and not the other. That looks exactly
     * like a request that reserved and never deducted, so a store that was merely a little behind produces a
     * violation nobody committed.
     */
    @Test
    fun `reads every field of one trace source in the same sampling round`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }
        val client = StubDeclaredObservationSourceClient { _, fields, _ ->
            fields.associateWith { DeclaredObservationRead.observed(emptyList<Map<String, Any>>()) }
        }

        val observed = reader(transport, client).read(
            specification(listOf(traceObservation("reserveSpans"), traceObservation("deductSpans"))),
            testTarget(),
            timedExecution(),
            "run-1",
            mapOf("traces" to traceSource()),
        )

        assertTrue(observed.getValue("reserveSpans").present)
        assertTrue(observed.getValue("deductSpans").present)
        assertEquals(1, client.requests.size)
        assertEquals(setOf("reserveSpans", "deductSpans"), client.requests.single().fields)
    }

    /**
     * Setup work is traced like any other request, but it is not what the invariants are about. A fixture-creating
     * call that happens to match an observation's query looks like half of a workload request.
     */
    @Test
    fun `starts the observation window at the workload rather than at setup`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }
        val client = StubDeclaredObservationSourceClient { _, fields, _ ->
            fields.associateWith { DeclaredObservationRead.observed(emptyList<Map<String, Any>>()) }
        }

        reader(transport, client).read(
            specification(traceObservation("reserveSpans")),
            testTarget(),
            execution().copy(
                timings = listOf(
                    StepTiming("product", SETUP_START, SETUP_END, StepRole.SETUP),
                    StepTiming("orders", WORKLOAD_START, WORKLOAD_END, StepRole.WORKLOAD),
                ),
            ),
            "run-1",
            mapOf("traces" to traceSource()),
        )

        val window = requireNotNull(client.requests.single().window)
        assertTrue(window.start.isAfter(SETUP_START))
    }

    private fun traceObservation(id: String) = Observation(
        id = id,
        label = id,
        sourceKind = ObservationSourceKind.DECLARED_SOURCE,
        sourceName = "traces",
        call = null,
        expression = id,
        readTiming = ReadTiming.IMMEDIATE,
    )

    private fun reader(
        transport: RecordingTransport,
        sourceClient: DeclaredObservationSourceClient = StubDeclaredObservationSourceClient(),
        maxObservationWait: Duration = Duration.ofMillis(SETTLE_BUDGET_MILLIS),
    ) = SpecObservationReader(
        values = SpecValueReader(
            caller = SpecHttpCaller(
                transport = transport,
                references = references,
                authProvider = StubAuthProvider(emptyMap()),
                settings = FixedSpecExecutionSettings(),
            ),
            evaluator = evaluator,
            settings = FixedSpecExecutionSettings(maxObservationWait = maxObservationWait),
        ),
        evaluator = evaluator,
        declaredSources = sourceClient,
        settings = FixedSpecExecutionSettings(maxObservationWait = maxObservationWait),
        clock = Clock.systemUTC(),
    )

    private fun harnessSource() = DeclaredObservationSource(
        name = "harness",
        kind = DeclaredObservationSourceKind.HARNESS_STATE,
        endpoint = "/harness/state",
        fields = setOf("redisHoldCount", "dbStock", "redisStock"),
        queries = emptyMap(),
        authProfile = null,
    )

    private fun traceSource() = DeclaredObservationSource(
        name = "traces",
        kind = DeclaredObservationSourceKind.TRACE,
        endpoint = "http://127.0.0.1:13200",
        fields = setOf("reserveSpans", "deductSpans"),
        queries = mapOf(
            "reserveSpans" to """{name="inventory.reserve" && span.arl.trial="${'$'}{trial}"}""",
            "deductSpans" to """{name="db.query" && span.arl.trial="${'$'}{trial}"}""",
        ),
        authProfile = null,
    )

    private fun twoConsecutive() = ReadTiming(
        rule = StabilityRule.TWO_CONSECUTIVE_EQUAL,
        maxWait = Duration.ofMillis(SETTLE_BUDGET_MILLIS),
        interval = Duration.ofMillis(20),
        evidence = null,
    )

    private fun apiObservation(readTiming: ReadTiming) = Observation(
        id = "dbStock",
        label = "남은 재고",
        sourceKind = ObservationSourceKind.API,
        sourceName = null,
        call = SpecHttpCall("GET", "/products/{{setup.product.productId}}", null, emptyMap(), null),
        expression = "response.body.stock",
        readTiming = readTiming,
    )

    private fun declaredObservation(id: String, field: String, readTiming: ReadTiming) = Observation(
        id = id,
        label = id,
        sourceKind = ObservationSourceKind.DECLARED_SOURCE,
        sourceName = "harness",
        call = null,
        expression = field,
        readTiming = readTiming,
    )

    private fun execution() = TrialExecution(
        trialNumber = 1,
        bindings = mapOf("runId" to "run-1", "trialNumber" to "1", "setup.product.productId" to "p-9"),
        responses = mapOf(
            "responses" to listOf(
                RecordedResponse(1, 201, 12, """{"items":[{"quantity":2}]}"""),
                RecordedResponse(2, 201, 15, """{"items":[{"quantity":1}]}"""),
            ),
        ),
        timings = emptyList(),
        stateChanged = true,
    )

    private fun specification(observation: Observation) = specification(listOf(observation))

    private fun specification(observations: List<Observation>) = TestSpecification(
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
        setup = emptyList(),
        workload = emptyList(),
        observations = observations,
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

    private fun timedExecution() = execution().copy(
        timings = listOf(StepTiming("orders", WORKLOAD_START, WORKLOAD_END)),
    )

    private companion object {
        const val SETTLE_BUDGET_MILLIS = 300L
        val SETUP_START: Instant = Instant.parse("2026-08-20T08:59:00Z")
        val SETUP_END: Instant = Instant.parse("2026-08-20T08:59:01Z")
        val WORKLOAD_START: Instant = Instant.parse("2026-08-20T09:00:00Z")
        val WORKLOAD_END: Instant = Instant.parse("2026-08-20T09:00:02Z")
    }
}
