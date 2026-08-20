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
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import tools.jackson.databind.ObjectMapper
import java.time.Duration
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

    private fun reader(transport: RecordingTransport) = SpecObservationReader(
        values = SpecValueReader(
            caller = SpecHttpCaller(
                transport = transport,
                references = references,
                authProvider = StubAuthProvider(emptyMap()),
                settings = FixedSpecExecutionSettings(),
            ),
            evaluator = evaluator,
            settings = FixedSpecExecutionSettings(maxObservationWait = Duration.ofMillis(SETTLE_BUDGET_MILLIS)),
        ),
        evaluator = evaluator,
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

    private fun specification(observation: Observation) = TestSpecification(
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
        observations = listOf(observation),
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
        const val SETTLE_BUDGET_MILLIS = 300L
    }
}
