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

/**
 * Whether a step prepared the trial or was the behaviour being judged.
 *
 * Setup work is real work: it calls the Target, and a traced Target records spans for it like any other request.
 * Those spans are not what the invariants are about, though, and letting them into the observation window makes a
 * fixture-creating call look like an unmatched half of the workload - which reads as a violation nobody committed.
 */
enum class StepRole {
    SETUP,
    WORKLOAD,
}

/** When a step started and finished, and whether it was setup or the workload under test. */
data class StepTiming(
    val name: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val role: StepRole = StepRole.WORKLOAD,
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
    /** Fault handles this trial injected but never released. The Runner must release these before the run ends. */
    val pendingFaultHandles: List<String> = emptyList(),
    val failure: String? = null,
) {
    val completed: Boolean = failure == null
}

/** The run could not be carried out as specified. Distinct from a violation: nothing was judged. */
class SpecExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
