package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationRead
import com.project.agenticreliabilitylab.testspec.domain.ObservationWindow
import com.project.agenticreliabilitylab.testspec.domain.ObservedSpan
import com.project.agenticreliabilitylab.testspec.domain.TraceReadLimits
import org.springframework.stereotype.Component

/**
 * Reads spans out of a Tempo search response.
 *
 * The response is untrusted input from a system nobody here controls, so every defect in it becomes a missing
 * observation rather than an exception or a guess. A trace store that answers in a shape this does not recognise
 * must make the invariants that depend on it unjudgeable; inventing a default would let a missing collector read
 * as a clean pass, which is the one outcome a reliability tool must never produce.
 *
 * The same rule governs *incomplete* answers, which are the more dangerous case because they look healthy. A
 * result that reached the query's limit, or a span set the store admits it trimmed, is refused rather than judged:
 * a race among two hundred requests is not disproved by finding no race among the fifty that were returned.
 *
 * Spans outside the trial's window are dropped here as well as in the query. Tempo returns whole traces, so a
 * trace that merely *starts* inside the window can carry spans from long before it, and those belong to work this
 * trial did not do.
 */
@Component
class TempoSpanParser {
    fun parse(
        sourceName: String,
        field: String,
        root: Map<String, Any?>,
        window: ObservationWindow,
        limits: TraceReadLimits,
    ): DeclaredObservationRead = try {
        val spans = collect(Context(sourceName, field, window, limits), root)
        DeclaredObservationRead.observed(
            spans.sortedWith(compareBy(ObservedSpan::startMs, ObservedSpan::traceId))
                .map(ObservedSpan::asBinding),
        )
    } catch (unreadable: UnreadableTraceResponse) {
        DeclaredObservationRead.missing(unreadable.message.orEmpty())
    }

    private fun collect(context: Context, root: Map<String, Any?>): List<ObservedSpan> {
        val traces = root[TRACES] as? List<*> ?: context.refuse("returned no traces array")
        if (traces.size >= context.limits.maxTraces) {
            context.refuse(
                "returned the maximum ${context.limits.maxTraces} traces, so the result is truncated",
            )
        }
        val spans = mutableListOf<ObservedSpan>()
        traces.forEach { trace ->
            val entry = trace as? Map<*, *> ?: context.refuse("returned a malformed trace")
            val traceId = entry[TRACE_ID] as? String ?: context.refuse("returned a trace with no id")
            spanElements(context, entry).forEach { element ->
                val span = span(context, traceId, element)
                if (span.startMs >= context.window.startMs() && span.startMs <= context.window.endMs()) {
                    spans.add(span)
                }
            }
            if (spans.size > context.limits.maxSpans) {
                context.refuse("returned more than ${context.limits.maxSpans} spans")
            }
        }
        return spans
    }

    /**
     * The span entries of one trace.
     *
     * Tempo moved from a single `spanSet` to a list of `spanSets`; both shapes are accepted. Anything else is
     * refused rather than read as an empty trace - a shape this does not understand carries exactly as much
     * evidence as a shape it never received, and treating it as "this trace had no matching spans" would turn a
     * parser that fell behind the store's format into a source of clean passes.
     */
    private fun spanElements(context: Context, trace: Map<*, *>): List<Any?> {
        val sets = when {
            trace[SPAN_SETS] != null -> (trace[SPAN_SETS] as? List<*>)?.map { set ->
                set as? Map<*, *> ?: context.refuse("returned a malformed span set")
            }
            trace[SPAN_SET] != null -> listOf(trace[SPAN_SET] as? Map<*, *>)
            else -> emptyList()
        } ?: context.refuse("returned a malformed span set list")
        return sets.flatMap { set -> spansOf(context, set ?: context.refuse("returned a malformed span set")) }
    }

    /**
     * The spans of one span set, refused when the store says it returned fewer than it matched.
     *
     * Tempo reports `matched` alongside the spans it chose to include. When that count exceeds what arrived, the
     * store is telling us plainly that this answer is partial, and a partial timeline cannot be judged.
     */
    private fun spansOf(context: Context, set: Map<*, *>): List<Any?> {
        val spans = set[SPANS] as? List<*> ?: context.refuse("returned a span set with no spans array")
        val matched = (set[MATCHED] as? Number)?.toInt()
        if (matched != null && matched > spans.size) {
            context.refuse("returned $matched matching spans but only ${spans.size} of them")
        }
        if (spans.size >= context.limits.maxSpansPerSet) {
            context.refuse(
                "returned the maximum ${context.limits.maxSpansPerSet} spans in one span set, " +
                    "so the result is truncated",
            )
        }
        return spans
    }

    /**
     * One span.
     *
     * A duration is optional because an instantaneous span is legitimate; a start time is not, because a span with
     * no position on the time axis cannot take part in any ordering judgement. A duration that is present but
     * unreadable is refused rather than treated as zero - the store said something here and we could not read it.
     */
    private fun span(context: Context, traceId: String, element: Any?): ObservedSpan {
        val entry = element as? Map<*, *> ?: context.refuse("returned an unreadable span")
        val startNanos = nanos(entry[START_TIME_UNIX_NANO]) ?: context.refuse("returned a span with no start time")
        val durationNanos = entry[DURATION_NANOS]?.let { raw ->
            nanos(raw) ?: context.refuse("returned a span with an unreadable duration")
        } ?: 0L
        if (durationNanos < 0) context.refuse("returned a span with a negative duration")
        return ObservedSpan(
            traceId = traceId,
            name = entry[NAME] as? String ?: context.field,
            startMs = startNanos / NANOS_PER_MILLI,
            endMs = (startNanos + durationNanos) / NANOS_PER_MILLI,
        )
    }

    /** Tempo renders 64-bit nanosecond values as JSON strings, so both a string and a number are accepted. */
    private fun nanos(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun ObservationWindow.startMs(): Long = start.toEpochMilli()

    private fun ObservationWindow.endMs(): Long = end.toEpochMilli()

    /** What every refusal message needs to name, carried so each reason reads the same way to an operator. */
    private data class Context(
        val sourceName: String,
        val field: String,
        val window: ObservationWindow,
        val limits: TraceReadLimits,
    ) {
        fun refuse(reason: String): Nothing =
            throw UnreadableTraceResponse("Trace source '$sourceName' $reason for '$field'")
    }

    private class UnreadableTraceResponse(message: String) : RuntimeException(message)

    private companion object {
        const val TRACES = "traces"
        const val TRACE_ID = "traceID"
        const val SPAN_SETS = "spanSets"
        const val SPAN_SET = "spanSet"
        const val SPANS = "spans"
        const val MATCHED = "matched"
        const val NAME = "name"
        const val START_TIME_UNIX_NANO = "startTimeUnixNano"
        const val DURATION_NANOS = "durationNanos"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
