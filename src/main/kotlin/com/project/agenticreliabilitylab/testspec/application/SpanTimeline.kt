package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.ObservedSpan

/**
 * The time-axis judgements an invariant can make about spans.
 *
 * A snapshot answers *what* the system ended up with; these answer *why* it got there. "Stock reached -3" and
 * "three requests read the same reservation within the same millisecond, and the write landed 340ms later" are the
 * same defect, but only the second one tells anybody what to change.
 *
 * Spans from different observations are matched by trace id. That is the only identifier that reliably ties one
 * request's work together across services, and pairing by position or by time would silently invent relationships
 * the trace data never claimed.
 *
 * **Every refusal here is deliberate.** These functions return false for a defect they saw and refuse to answer
 * for a timeline they cannot read, but they never return true for something they did not check. A trace with no
 * counterpart, a timeline with nothing to compare against, and a store that answered partially all produce a
 * refusal, because a Target with no instrumentation would otherwise report a clean pass for a property nobody
 * measured - and a clean pass is the one answer a reliability tool must never invent.
 */
object SpanTimeline {
    const val NO_OVERLAP = "noOverlap"
    const val ORDERED = "ordered"
    const val MAX_START_LAG_MS = "maxStartLagMs"
    const val TRACE_COUNT = "traceCount"

    /** The function names an invariant may call on span lists. */
    val FUNCTIONS: Set<String> = setOf(NO_OVERLAP, ORDERED, MAX_START_LAG_MS, TRACE_COUNT)

    /**
     * How many distinct traces the observation carries.
     *
     * This is what lets a specification state its own completeness expectation - `traceCount(reserveSpans) ==
     * {{워크로드.orders.요청수}}` - instead of leaving the engine to guess how much of an eventually consistent
     * trace store had caught up. The engine cannot know how many traces *should* exist; the specification that
     * declared the workload can. Without this, a store that has ingested three of two hundred traces settles into
     * a perfectly consistent, perfectly wrong pass.
     */
    fun traceCount(values: List<*>): Long =
        spans(values, TRACE_COUNT).mapTo(mutableSetOf(), ObservedSpan::traceId).size.toLong()

    /**
     * True when no other trace began its [first] span while one trace was still between its own [first] and
     * [second] spans.
     *
     * This is the interleaving question: one order reserves stock and later deducts it, and the property being
     * checked is that no other order slipped its reservation into that gap. Simultaneous starts count as
     * interleaved - two requests reading the same value in the same millisecond is precisely the race being
     * hunted, not a coincidence to forgive.
     *
     * A retry inside one trace opens a *second* critical section rather than extending the first, so attempts are
     * paired in order: each [first] span is matched to the next [second] span that follows it. Taking only each
     * trace's earliest span would shrink every window to the first attempt and hide interleaving that happened
     * during a retry - which is where contention is most likely in the first place.
     */
    fun noOverlap(first: List<*>, second: List<*>): Boolean {
        val starts = startsByTrace(first, NO_OVERLAP)
        if (starts.size < TRACES_NEEDED_TO_INTERLEAVE) {
            unjudgeable(NO_OVERLAP, "only ${starts.size} trace carries the first span, so nothing could interleave")
        }
        val attempts = pair(starts, startsByTrace(second, NO_OVERLAP)).attempts
        if (attempts.isEmpty()) unjudgeable(NO_OVERLAP, "no trace carries both spans")
        return attempts.none { attempt ->
            starts.any { (other, otherStarts) ->
                other != attempt.traceId && otherStarts.any { start -> start >= attempt.from && start < attempt.until }
            }
        }
    }

    /**
     * True when every [second] span has a [first] span in the same trace that did not start after it.
     *
     * A second span with no first span in its trace is out of order rather than ignorable: something completed a
     * step whose prerequisite was never recorded, which is a stronger finding than a late one.
     */
    fun ordered(first: List<*>, second: List<*>): Boolean {
        val starts = startsByTrace(first, ORDERED)
        val followers = startsByTrace(second, ORDERED)
        if (followers.isEmpty()) unjudgeable(ORDERED, "no trace carries the following span")
        return followers.all { (trace, times) ->
            starts[trace]?.let { first -> first.min() <= times.min() } == true
        }
    }

    /**
     * The largest gap, in milliseconds, between a trace's [first] span starting and its [second] span starting.
     *
     * This is the number that turns "the stock was wrong" into "the deduction landed 340ms after the reservation".
     *
     * A trace whose [first] span never got a [second] span refuses the whole judgement rather than being left out.
     * Excluding it would measure the lag of the work that completed and call that the answer, which reads as a
     * pass precisely when a reservation was never deducted at all - a worse defect than any delay this function
     * was built to measure. The refusal says so plainly instead.
     */
    fun maxStartLagMs(first: List<*>, second: List<*>): Long {
        val pairing = pair(startsByTrace(first, MAX_START_LAG_MS), startsByTrace(second, MAX_START_LAG_MS))
        if (pairing.unpaired.isNotEmpty()) {
            unjudgeable(
                MAX_START_LAG_MS,
                "${pairing.unpaired.size} trace(s) started the first span without ever reaching the second, " +
                    "so the gap cannot be measured",
            )
        }
        if (pairing.attempts.isEmpty()) unjudgeable(MAX_START_LAG_MS, "no trace carries both spans")
        return pairing.attempts.maxOf { attempt -> attempt.until - attempt.from }
    }

    /**
     * Matches each first span to the next second span that follows it, within one trace.
     *
     * Both halves are walked in time order, so a trace that reserved twice and deducted twice yields two separate
     * critical sections rather than one span covering both.
     */
    private fun pair(starts: Map<String, List<Long>>, followers: Map<String, List<Long>>): Pairing {
        val attempts = mutableListOf<Attempt>()
        val unpaired = mutableSetOf<String>()
        starts.forEach { (trace, firstStarts) ->
            val secondStarts = followers[trace].orEmpty().sorted()
            var next = 0
            firstStarts.sorted().forEach { from ->
                while (next < secondStarts.size && secondStarts[next] < from) next++
                if (next < secondStarts.size) {
                    attempts.add(Attempt(trace, from, secondStarts[next]))
                    next++
                } else {
                    unpaired.add(trace)
                }
            }
        }
        return Pairing(attempts, unpaired)
    }

    /** Every time each trace entered this span, retries included. */
    private fun startsByTrace(values: List<*>, function: String): Map<String, List<Long>> =
        spans(values, function).groupBy(ObservedSpan::traceId)
            .mapValues { (_, spans) -> spans.map(ObservedSpan::startMs) }

    private fun spans(values: List<*>, function: String): List<ObservedSpan> = values.map { value ->
        val entry = value as? Map<*, *> ?: unjudgeable(function, "a span was not readable")
        ObservedSpan(
            traceId = entry[ObservedSpan.TRACE_ID] as? String
                ?: unjudgeable(function, "a span carries no trace id"),
            name = entry[ObservedSpan.NAME] as? String ?: "",
            startMs = millis(entry[ObservedSpan.START_MS], function),
            endMs = millis(entry[ObservedSpan.END_MS], function),
        )
    }

    private fun millis(value: Any?, function: String): Long = (value as? Number)?.toLong()
        ?: unjudgeable(function, "a span carries no usable timestamp")

    /**
     * Refuses to answer.
     *
     * The caller turns this into an unjudged invariant rather than a violation, which is the honest outcome: we
     * did not observe a defect, and we did not observe its absence either.
     */
    private fun unjudgeable(function: String, reason: String): Nothing =
        throw UnjudgeableObservationException("'$function' cannot be judged: $reason")

    /** One trace's critical section: from its first span starting until its matching second span started. */
    private data class Attempt(val traceId: String, val from: Long, val until: Long)

    private data class Pairing(val attempts: List<Attempt>, val unpaired: Set<String>)

    /** Interleaving is a question about two traces. One trace cannot answer it either way. */
    private const val TRACES_NEEDED_TO_INTERLEAVE = 2
}
