package com.project.agenticreliabilitylab.testspec.domain

import java.time.Instant

/**
 * One response the run received, or the record that a request never produced one.
 *
 * A request that failed to deliver is kept in the list rather than dropped. Ten requests that produced eight
 * responses is a different fact from eight requests, and dropping the two would let an invariant counting
 * successes and failures balance when it should not.
 */
data class RecordedResponse(
    val requestNumber: Int,
    val statusCode: Int,
    val durationMs: Long,
    val body: String,
    val failure: String? = null,
) {
    val delivered: Boolean = failure == null
}

/** When a step started and finished, kept for the time-axis functions a later phase adds. */
data class StepTiming(
    val name: String,
    val startedAt: Instant,
    val endedAt: Instant,
)

/**
 * Everything one trial produced.
 *
 * [stateChanged] records whether any non-read request was *attempted*, not whether it succeeded. A request whose
 * outcome is unknown may still have changed the Target, so cleanup has to assume it did.
 *
 * [failure] is set when the trial could not be carried out as specified. It is carried on the result rather than
 * thrown, because a trial that died halfway may already have changed the Target and the caller still has to see
 * what ran in order to clean up after it.
 */
data class TrialExecution(
    val trialNumber: Int,
    val bindings: Map<String, String>,
    val responses: Map<String, List<RecordedResponse>>,
    val timings: List<StepTiming>,
    val stateChanged: Boolean,
    val failure: String? = null,
) {
    val completed: Boolean = failure == null
}

/** The run could not be carried out as specified. Distinct from a violation: nothing was judged. */
class SpecExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
