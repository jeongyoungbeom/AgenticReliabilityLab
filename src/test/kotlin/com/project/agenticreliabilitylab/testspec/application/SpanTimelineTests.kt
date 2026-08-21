package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.ObservedSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpanTimelineTests {
    @Test
    fun `accepts reservations that never interleave with another order's deduction`() {
        val reserve = spans("t1" to 100L, "t2" to 300L)
        val deduct = spans("t1" to 150L, "t2" to 350L)

        assertTrue(SpanTimeline.noOverlap(reserve, deduct))
    }

    @Test
    fun `reports the interleaving that produced the wrong stock`() {
        val reserve = spans("t1" to 100L, "t2" to 120L, "t3" to 130L)
        val deduct = spans("t1" to 400L, "t2" to 420L, "t3" to 430L)

        assertFalse(SpanTimeline.noOverlap(reserve, deduct))
    }

    @Test
    fun `treats two reservations in the same millisecond as interleaved`() {
        val reserve = spans("t1" to 100L, "t2" to 100L)
        val deduct = spans("t1" to 200L, "t2" to 200L)

        assertFalse(SpanTimeline.noOverlap(reserve, deduct))
    }

    @Test
    fun `refuses to judge a timeline that recorded nothing`() {
        val failure = assertFailsWith<SpecExpressionException> {
            SpanTimeline.noOverlap(emptyList<Any>(), emptyList<Any>())
        }

        assertTrue(failure.message.orEmpty().contains("cannot be judged"))
    }

    @Test
    fun `refuses to judge an ordering with no following span`() {
        assertFailsWith<SpecExpressionException> {
            SpanTimeline.ordered(spans("t1" to 100L), emptyList<Any>())
        }
    }

    @Test
    fun `accepts an ordering where every deduction follows its own reservation`() {
        assertTrue(SpanTimeline.ordered(spans("t1" to 100L, "t2" to 200L), spans("t1" to 150L, "t2" to 250L)))
    }

    @Test
    fun `rejects a deduction whose reservation was never recorded`() {
        assertFalse(SpanTimeline.ordered(spans("t1" to 100L), spans("t2" to 150L)))
    }

    @Test
    fun `measures the largest gap between a reservation and its deduction`() {
        val reserve = spans("t1" to 100L, "t2" to 200L)
        val deduct = spans("t1" to 180L, "t2" to 540L)

        assertEquals(340L, SpanTimeline.maxStartLagMs(reserve, deduct))
    }

    @Test
    fun `refuses to judge interleaving when only one trace was observed`() {
        val failure = assertFailsWith<SpecExpressionException> {
            SpanTimeline.noOverlap(spans("t1" to 100L), spans("t1" to 200L))
        }

        assertTrue(failure.message.orEmpty().contains("nothing could interleave"))
    }

    @Test
    fun `finds an order that slipped into a retried reservation`() {
        val reserve = spans("t1" to 0L, "t1" to 500L, "t2" to 550L)
        val deduct = spans("t1" to 50L, "t1" to 600L, "t2" to 600L)

        assertFalse(SpanTimeline.noOverlap(reserve, deduct))
    }

    @Test
    fun `refuses to measure a gap when a reservation never reached its deduction`() {
        val reserve = spans("t1" to 100L, "t2" to 200L)
        val deduct = spans("t1" to 180L)

        val failure = assertFailsWith<SpecExpressionException> {
            SpanTimeline.maxStartLagMs(reserve, deduct)
        }

        assertTrue(failure.message.orEmpty().contains("without ever reaching the second"))
    }

    @Test
    fun `refuses to measure a gap when no trace carries both spans`() {
        assertFailsWith<SpecExpressionException> {
            SpanTimeline.maxStartLagMs(spans("t1" to 100L), spans("t2" to 200L))
        }
    }

    @Test
    fun `counts the distinct traces an observation carries`() {
        assertEquals(2L, SpanTimeline.traceCount(spans("t1" to 0L, "t1" to 500L, "t2" to 550L)))
        assertEquals(0L, SpanTimeline.traceCount(emptyList<Any>()))
    }

    @Test
    fun `evaluates the time axis functions through the expression environment`() {
        val environment = SpecExpressionEnvironment()
        val bindings = mapOf<String, Any>(
            "reserveSpans" to spans("t1" to 100L, "t2" to 300L),
            "deductSpans" to spans("t1" to 180L, "t2" to 380L),
        )

        val identifiers = bindings.keys
        assertTrue(
            environment.compile("noOverlap(reserveSpans, deductSpans)", identifiers).evaluateBoolean(bindings),
        )
        assertTrue(
            environment.compile("ordered(reserveSpans, deductSpans)", identifiers).evaluateBoolean(bindings),
        )
        assertEquals(
            true,
            environment.compile("maxStartLagMs(reserveSpans, deductSpans) <= 100", identifiers)
                .evaluateBoolean(bindings),
        )
        assertEquals(
            true,
            environment.compile("traceCount(reserveSpans) == 2", identifiers).evaluateBoolean(bindings),
        )
    }

    /**
     * The reason a time-axis function refused has to survive the expression runtime.
     *
     * "No trace carries both spans" sends an operator to the collector; "3 traces never reached the second span"
     * sends them to the code. Reporting both as a bare exception type would send them to the specification, which
     * is the one place the fault is not.
     */
    @Test
    fun `carries the refusal reason out through the expression runtime`() {
        val environment = SpecExpressionEnvironment()
        val bindings = mapOf<String, Any>(
            "reserveSpans" to spans("t1" to 100L),
            "deductSpans" to spans("t1" to 200L),
        )

        val failure = assertFailsWith<SpecExpressionException> {
            environment.compile("noOverlap(reserveSpans, deductSpans)", bindings.keys).evaluateBoolean(bindings)
        }

        assertTrue(failure.message.orEmpty().contains("nothing could interleave"))
    }

    @Test
    fun `refuses an expression naming a function the environment does not provide`() {
        assertFailsWith<SpecExpressionException> {
            SpecExpressionEnvironment().compile("overlaps(reserveSpans, reserveSpans)", setOf("reserveSpans"))
        }
    }

    private fun spans(vararg starts: Pair<String, Long>): List<Map<String, Any>> = starts.map { (trace, start) ->
        ObservedSpan(traceId = trace, name = "inventory.reserve", startMs = start, endMs = start + SPAN_LENGTH_MS)
            .asBinding()
    }

    private companion object {
        const val SPAN_LENGTH_MS = 5L
    }
}
