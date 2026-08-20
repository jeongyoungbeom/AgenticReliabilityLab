package com.project.agenticreliabilitylab.testspec.domain

import java.time.Duration

/**
 * How this Target's environment is put back to a known state.
 *
 * This is Profile data, not specification data. What a customer is asked for is not "write cleanup code" but
 * "tell us how to reset", because a single order leaves traces across payment, settlement, shipping and
 * notification, and ARL only ever knows about the one request it sent.
 */
data class ResetPlan(
    val method: CleanupMethod,
    val hook: SpecHttpCall?,
    val expectedDuration: Duration,
    val verifications: List<ResetVerification>,
) {
    companion object {
        /** For a specification that never changes anything, so there is nothing to undo. */
        val NOT_REQUIRED = ResetPlan(CleanupMethod.NOT_REQUIRED, null, Duration.ZERO, emptyList())
    }
}

/**
 * One thing that must be true again after a reset.
 *
 * A reset with no verification is an assumption, and an unverified assumption is exactly what makes the *next*
 * run's verdict meaningless: leftover state from this run would be read as the next run's behaviour.
 */
data class ResetVerification(
    val id: String,
    val call: SpecHttpCall,
    val expression: String,
    val condition: String,
    val readTiming: ReadTiming,
) {
    init {
        require(ASCII_IDENTIFIER.matches(id)) { "Reset check id '$id' must be an ASCII identifier" }
    }

    private companion object {
        val ASCII_IDENTIFIER = Regex("[_a-zA-Z][_a-zA-Z0-9]*")
    }
}

/** What one reset check saw. */
data class ResetCheck(
    val id: String,
    val condition: String,
    val observed: String,
    val satisfied: Boolean,
)

/**
 * How the cleanup went.
 *
 * [verified] false is not a warning. The Target is left in an unknown state, so the next run has to be blocked
 * rather than allowed to produce a verdict nobody can trust.
 */
data class ResetOutcome(
    val performed: Boolean,
    val verified: Boolean,
    val checks: List<ResetCheck>,
    val failure: String? = null,
)

/**
 * Everything one run of a specification produced.
 *
 * [cleanupVerified] is kept next to the verdict rather than inside it because it governs a different decision:
 * the verdict says what this run found, and this says whether the next run is allowed to happen at all.
 */
data class SpecRunOutcome(
    val runId: String,
    val result: SpecificationResult,
    val executions: List<TrialExecution>,
    val resets: List<ResetOutcome>,
    val cleanupVerified: Boolean,
)
