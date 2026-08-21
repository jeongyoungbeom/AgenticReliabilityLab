package com.project.agenticreliabilitylab.testspec.domain

import java.time.Duration
import java.time.Instant

/**
 * The slice of time a trial's observations may look at.
 *
 * A trace store holds every trace the environment ever produced, including the ones an earlier trial or an
 * unrelated developer created. Judging a trial against those would report someone else's behaviour as this
 * specification's verdict, so the engine - not the specification - fixes the window from what this trial actually
 * ran. A specification cannot widen it.
 */
data class ObservationWindow(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(!end.isBefore(start)) { "An observation window cannot end before it starts" }
    }

    companion object {
        /**
         * When the trial's workload began, or null when no workload step ran.
         *
         * Setup steps are excluded. They call the Target and a traced Target records spans for them, but those
         * spans belong to fixture creation rather than to the behaviour being judged - and a setup call that
         * happens to match an observation's query looks exactly like a workload request that completed only half
         * of its work, which is reported as a violation nobody committed.
         */
        fun workloadStart(timings: List<StepTiming>): Instant? = timings
            .filter { timing -> timing.role == StepRole.WORKLOAD }
            .minOfOrNull(StepTiming::startedAt)

        /**
         * The window a read at [readAt] may look at.
         *
         * The window ends when the read happens rather than when the workload finished. Observations wait for a
         * trace store to catch up - up to a minute - and a window that closed seconds after the last request would
         * discard exactly the spans that waiting was meant to collect. Worse, it would discard them in proportion
         * to how late they were, so a Target whose propagation degraded would look *more* compliant, not less.
         *
         * [margin] covers clock skew between ARL and whatever collected the Target's spans, which is real and
         * unmeasured here. It is deliberately generous at the start: over-collecting inside one trial is cheap
         * because spans are matched by trace, while under-collecting silently hides evidence.
         */
        fun spanning(workloadStart: Instant, readAt: Instant, margin: Duration): ObservationWindow =
            ObservationWindow(workloadStart.minus(margin), readAt.plus(margin))
    }
}

/**
 * How a trial's own spans are told apart from everything else a trace store holds.
 *
 * A time window is not enough. A trace store holds every trace the environment produced, and a query that matches
 * by span name alone also matches another developer's request, a previous trial, and this trial's own setup work -
 * all of which can land inside the window. Judging those reports someone else's behaviour as this specification's
 * verdict, and the sample Profile's `{name="db.query" && span.db.table="products"}` matches the setup call that
 * creates the product fixture, so this is not a hypothetical.
 *
 * The Target therefore has to cooperate: ARL sends [HEADER] on the workload requests it is judging, and a Target
 * that wants trace-based invariants records that value as a span attribute. This is the same bargain HARNESS_STATE
 * already makes by requiring a `/harness/state` endpoint - a source kind is available exactly when the Target has
 * done its half.
 *
 * The header goes on workload requests **only**. Setup carries the same run and trial, so scoping by run alone
 * would leave fixture creation looking like a workload request that started work it never finished.
 */
object TraceScope {
    /** Put on the workload requests a trial is judging, and on nothing else. */
    const val HEADER = "X-ARL-Trial"

    /** What a Profile's TraceQL must contain. The engine substitutes it; a specification never sees it. */
    const val PLACEHOLDER = "\${trial}"

    /** What the Target records, and what the substituted query matches against. */
    fun of(runId: String, trialNumber: Int): String = "$runId/$trialNumber"

    /**
     * True when a value is safe to place inside a TraceQL string literal.
     *
     * The engine builds this value itself from a run id and a trial number, so this can only fail if that changes.
     * It is checked anyway: a query is the one place a stray quote turns a bounded read into an arbitrary one.
     */
    fun isQuotable(value: String): Boolean = value.none { character -> character == '"' || character == '\\' }
}

/**
 * The bounds a trace read is allowed to return within.
 *
 * These exist to make truncation *detectable*, not merely to cap memory. A trace store answers a query with at most
 * as many traces as it was asked for and says nothing about what it left out, so a result that reaches the limit is
 * indistinguishable from a complete one unless the reader treats "exactly the maximum" as a refusal to judge.
 * Reading fewer traces than happened and calling the result a pass is the failure this whole design exists to
 * prevent, and it is far more likely than running out of memory.
 */
data class TraceReadLimits(
    val maxTraces: Int,
    val maxSpansPerSet: Int,
    val maxSpans: Int,
)

/**
 * One span a trace source reported.
 *
 * This exists so a verdict can say *why* something happened rather than only *what*: a snapshot shows a stock of
 * -3, while spans show that three requests read the same reservation before any of them wrote it back. Spans are
 * matched across observations by [traceId], because that is the only identifier that reliably ties one request's
 * work together across services.
 */
data class ObservedSpan(
    val traceId: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long = endMs - startMs

    /**
     * The shape an expression sees.
     *
     * Only strings and longs are used. The expression runtime adapts primitives, lists and maps; handing it a
     * Kotlin class would make an invariant fail at evaluation time rather than at compile time, which is exactly
     * the failure this design refuses to allow.
     */
    fun asBinding(): Map<String, Any> = mapOf(
        TRACE_ID to traceId,
        NAME to name,
        START_MS to startMs,
        END_MS to endMs,
        DURATION_MS to durationMs,
    )

    companion object {
        const val TRACE_ID = "traceId"
        const val NAME = "name"
        const val START_MS = "startMs"
        const val END_MS = "endMs"
        const val DURATION_MS = "durationMs"
    }
}
