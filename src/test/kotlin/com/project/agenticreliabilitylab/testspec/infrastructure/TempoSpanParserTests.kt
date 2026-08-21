package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.domain.ObservationWindow
import com.project.agenticreliabilitylab.testspec.domain.ObservedSpan
import com.project.agenticreliabilitylab.testspec.domain.TraceReadLimits
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests are mostly about what the parser *refuses*.
 *
 * A trace store that answers partially answers in a shape that looks entirely healthy - a shorter list. Every
 * refusal here exists because the alternative is a timeline that reads as complete, gets judged, and reports a
 * clean pass for the requests nobody saw.
 */
class TempoSpanParserTests {
    @Test
    fun `reads the spans a trace reported`() {
        val read = parse(traces(trace("t1", span(startNanos = SECOND_ONE, durationNanos = 5_000_000))))

        assertTrue(read.present)
        val spans = read.value as List<*>
        val first = spans.single() as Map<*, *>
        assertEquals("t1", first[ObservedSpan.TRACE_ID])
        assertEquals(1_000L, first[ObservedSpan.START_MS])
        assertEquals(5L, first[ObservedSpan.DURATION_MS])
    }

    @Test
    fun `accepts the singular spanSet shape`() {
        val trace = mapOf(
            "traceID" to "t1",
            "spanSet" to mapOf("spans" to listOf(span(startNanos = SECOND_ONE))),
        )

        val read = parse(traces(trace))

        assertTrue(read.present)
        assertEquals(1, (read.value as List<*>).size)
    }

    /**
     * A result that reached the limit is the store saying "there may be more", in the only way its API can.
     */
    @Test
    fun `refuses a trace list that reached the query limit`() {
        val many = (1..LIMITS.maxTraces).map { index -> trace("t$index", span(startNanos = SECOND_ONE)) }

        val read = parse(traces(*many.toTypedArray()))

        assertFalse(read.present)
        assertTrue(read.failure.orEmpty().contains("truncated"))
    }

    @Test
    fun `refuses a span set the store admits it trimmed`() {
        val trace = mapOf(
            "traceID" to "t1",
            "spanSets" to listOf(
                mapOf("spans" to listOf(span(startNanos = SECOND_ONE)), "matched" to 7),
            ),
        )

        val read = parse(traces(trace))

        assertFalse(read.present)
        assertTrue(read.failure.orEmpty().contains("only 1 of them"))
    }

    /**
     * A shape the parser does not recognise carries exactly as much evidence as a response that never arrived.
     * Reading it as "this trace had no matching spans" would turn a parser that fell behind Tempo's format into a
     * quiet source of passes.
     */
    @Test
    fun `refuses a span set list it cannot read instead of reading it as empty`() {
        val read = parse(traces(mapOf("traceID" to "t1", "spanSets" to "not-a-list")))

        assertFalse(read.present)
        assertTrue(read.failure.orEmpty().contains("malformed span set"))
    }

    @Test
    fun `refuses a span with no start time`() {
        val read = parse(traces(trace("t1", mapOf("name" to "inventory.reserve"))))

        assertFalse(read.present)
        assertTrue(read.failure.orEmpty().contains("no start time"))
    }

    @Test
    fun `refuses a span whose duration cannot be read rather than treating it as zero`() {
        val malformed = mapOf("startTimeUnixNano" to "$SECOND_ONE", "durationNanos" to "not-a-number")

        val read = parse(traces(trace("t1", malformed)))

        assertFalse(read.present)
        assertTrue(read.failure.orEmpty().contains("unreadable duration"))
    }

    @Test
    fun `refuses a trace with no id`() {
        val read = parse(traces(mapOf("spanSets" to emptyList<Any>())))

        assertFalse(read.present)
        assertTrue(read.failure.orEmpty().contains("no id"))
    }

    @Test
    fun `drops spans that started outside the trial window`() {
        val inside = trace("t1", span(startNanos = SECOND_ONE))
        val before = trace("t2", span(startNanos = 0L))

        val read = parse(traces(inside, before))

        assertTrue(read.present)
        assertEquals(1, (read.value as List<*>).size)
    }

    private fun parse(root: Map<String, Any?>) =
        TempoSpanParser().parse("traces", "reserveSpans", root, WINDOW, LIMITS)

    private fun traces(vararg entries: Any): Map<String, Any?> = mapOf("traces" to entries.toList())

    private fun trace(id: String, vararg spans: Map<String, Any?>): Map<String, Any?> = mapOf(
        "traceID" to id,
        "spanSets" to listOf(mapOf("spans" to spans.toList())),
    )

    private fun span(startNanos: Long, durationNanos: Long = 0L): Map<String, Any?> = mapOf(
        "name" to "inventory.reserve",
        "startTimeUnixNano" to "$startNanos",
        "durationNanos" to "$durationNanos",
    )

    private companion object {
        const val SECOND_ONE = 1_000_000_000L
        val WINDOW = ObservationWindow(Instant.ofEpochMilli(500), Instant.ofEpochMilli(60_000))
        val LIMITS = TraceReadLimits(maxTraces = 200, maxSpansPerSet = 100, maxSpans = 1_000)
    }
}
