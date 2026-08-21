package com.project.agenticreliabilitylab.testspec.domain

/** What the engine concluded about one invariant. */
enum class InvariantOutcome {
    PASSED,
    VIOLATED,

    /** We could not judge. Not a defect - an absent observation or an unmet precondition. */
    NOT_EVALUATED,
}

/** Why an invariant could not be judged. Kept apart from the outcome so a report can say what to fix. */
enum class NotEvaluatedReason {
    /** An observation this invariant reads was never successfully read. */
    OBSERVATION_MISSING,

    /** The invariant named in `requires` did not pass. */
    REQUIREMENT_UNMET,

    /**
     * The observation was read, but it cannot support a judgement either way.
     *
     * Kept apart from [EXPRESSION_FAILED] because the two send an operator to opposite places. A failed
     * expression means the specification is wrong; this means the specification is fine and the evidence is thin -
     * a collector that is not running, a trace store that has not caught up, a reservation that never reached its
     * deduction. Reporting the second as the first sends someone to edit a correct specification.
     */
    OBSERVATION_INSUFFICIENT,

    /** The expression could not be evaluated against the observed values. */
    EXPRESSION_FAILED,

    /** The trial never ran to completion, so there was nothing to judge. */
    TRIAL_NOT_RUN,
}

/**
 * One invariant's verdict, with the evidence behind it.
 *
 * "The concurrency test failed" is not something an operator can act on, so every verdict carries the expression
 * that was judged and the values it saw.
 */
data class InvariantVerdict(
    val invariantId: String,
    val description: String,
    val outcome: InvariantOutcome,
    val condition: String,
    val observedValues: Map<String, String>,
    val notEvaluatedReason: NotEvaluatedReason? = null,
    val detail: String? = null,
    /** Set when an exception the reviewer approved turned a violation into a pass. */
    val appliedException: String? = null,
)

/** How one trial of a specification came out. */
enum class TrialOutcome {
    PASSED,
    VIOLATED,

    /** Nothing was violated but something could not be judged. */
    INCONCLUSIVE,
}

/**
 * One observed value exactly as the verdict saw it.
 *
 * A verdict keeps only a rendered summary of what it judged, because a summary is what an operator reads and what
 * a row can hold. This keeps the value itself, and it exists for one reason: a verdict can say "the deduction
 * landed 340ms after the reservation" while the spans that show it are already gone. An improvement suggestion has
 * to reason from the evidence, not from a sentence about the evidence.
 *
 * [omitted] is set when [value] was dropped for size. Dropping it silently would leave a record that looks
 * complete, which is the same failure this type exists to prevent, one level up.
 */
data class ObservedEvidence(
    val present: Boolean,
    val display: String,
    val value: Any? = null,
    val omitted: String? = null,
)

/** One run of the whole specification. */
data class TrialResult(
    val trialNumber: Int,
    val outcome: TrialOutcome,
    val verdicts: List<InvariantVerdict>,
    val observations: Map<String, ObservedEvidence> = emptyMap(),
)

/**
 * The verdict over every trial.
 *
 * A probabilistic defect shows up in some trials and not others, so `20회 중 3회 위반` and `1회 위반` are different
 * facts and both are kept. Reporting only the final outcome would throw away how reproducible the defect is.
 */
data class SpecificationResult(
    val outcome: TrialOutcome,
    val trialsRun: Int,
    val trialsViolated: Int,
    val trialsInconclusive: Int,
    val trials: List<TrialResult>,
) {
    val reason: String = when (outcome) {
        TrialOutcome.VIOLATED -> "$trialsRun trial(s) run, $trialsViolated violated"
        TrialOutcome.INCONCLUSIVE -> "$trialsRun trial(s) run, $trialsInconclusive could not be judged"
        TrialOutcome.PASSED -> "$trialsRun trial(s) run, no violation observed"
    }
}
