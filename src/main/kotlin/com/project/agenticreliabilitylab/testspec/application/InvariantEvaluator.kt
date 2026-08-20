package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.Invariant
import com.project.agenticreliabilitylab.testspec.domain.InvariantOutcome
import com.project.agenticreliabilitylab.testspec.domain.InvariantVerdict
import com.project.agenticreliabilitylab.testspec.domain.NotEvaluatedReason
import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import com.project.agenticreliabilitylab.testspec.domain.SpecificationResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import com.project.agenticreliabilitylab.testspec.domain.TrialResult
import org.springframework.stereotype.Component

/**
 * Decides whether the values a run observed satisfy the specification's invariants.
 *
 * This is the one place a pass or fail is decided, and no model participates. The same observations always produce
 * the same verdict, which is what makes "this test passed" mean anything at all. Being deterministic is not the
 * same as being right: a wrong invariant produces a confidently wrong verdict, which is why a human approves the
 * judging rules before any of this runs.
 */
@Component
class InvariantEvaluator(
    private val expressions: SpecExpressionEnvironment,
    private val references: SpecReferenceResolver,
) {
    /**
     * Judges one trial.
     *
     * Invariants are judged in declaration order so a `requires` can only name one already decided. An unmet
     * requirement makes the dependent invariant NOT_EVALUATED rather than violated - a value we could not read is
     * not a defect, and reporting it as one would teach operators to distrust the tool.
     */
    fun judgeTrial(
        specification: TestSpecification,
        trialNumber: Int,
        observed: Map<String, ObservedValue>,
    ): TrialResult {
        val verdicts = mutableListOf<InvariantVerdict>()
        val decided = mutableMapOf<String, InvariantOutcome>()
        val staticBindings = references.staticBindings(specification)

        specification.invariants.forEach { invariant ->
            val verdict = judge(invariant, observed, decided, staticBindings)
            decided[invariant.id] = verdict.outcome
            verdicts.add(verdict)
        }
        return TrialResult(trialNumber, outcomeOf(verdicts), verdicts)
    }

    /**
     * The verdicts for a trial that never ran.
     *
     * Every invariant is reported as unjudged rather than the trial being left out of the result. A run of 20
     * trials where 5 died has to look different from a run of 15, or a flaky environment would quietly read as
     * a clean pass.
     */
    fun unrunnable(specification: TestSpecification, trialNumber: Int, reason: String): TrialResult = TrialResult(
        trialNumber,
        TrialOutcome.INCONCLUSIVE,
        specification.invariants.map { invariant ->
            InvariantVerdict(
                invariantId = invariant.id,
                description = invariant.description,
                outcome = InvariantOutcome.NOT_EVALUATED,
                condition = invariant.condition,
                observedValues = emptyMap(),
                notEvaluatedReason = NotEvaluatedReason.TRIAL_NOT_RUN,
                detail = reason,
            )
        },
    )

    /**
     * Combines trials. One violating trial fails the specification, because a defect that appears sometimes is
     * a defect.
     */
    fun combine(specification: TestSpecification, trials: List<TrialResult>): SpecificationResult {
        val violated = trials.count { it.outcome == TrialOutcome.VIOLATED }
        val inconclusive = trials.count { it.outcome == TrialOutcome.INCONCLUSIVE }
        val outcome = when {
            specification.policy.aggregation == TrialAggregation.ANY_VIOLATION_FAILS && violated > 0 ->
                TrialOutcome.VIOLATED
            inconclusive > 0 -> TrialOutcome.INCONCLUSIVE
            else -> TrialOutcome.PASSED
        }
        return SpecificationResult(outcome, trials.size, violated, inconclusive, trials)
    }

    private fun judge(
        invariant: Invariant,
        observed: Map<String, ObservedValue>,
        decided: Map<String, InvariantOutcome>,
        staticBindings: Map<String, String>,
    ): InvariantVerdict {
        invariant.requires?.let { required ->
            if (decided[required] != InvariantOutcome.PASSED) {
                return notEvaluated(
                    invariant, invariant.condition, observed, NotEvaluatedReason.REQUIREMENT_UNMET,
                    "'$required' did not pass, so this could not be judged",
                )
            }
        }

        val missing = observed.filterValues { !it.present }.keys
        val bindings = observed.filterValues { it.present }.mapValues { (_, value) -> value.value!! }

        return try {
            evaluate(invariant, bindings, observed, staticBindings)
        } catch (exception: SpecExpressionException) {
            val reason = if (missing.isEmpty()) {
                NotEvaluatedReason.EXPRESSION_FAILED
            } else {
                NotEvaluatedReason.OBSERVATION_MISSING
            }
            notEvaluated(invariant, invariant.condition, observed, reason, exception.message)
        } catch (exception: SpecExecutionException) {
            notEvaluated(
                invariant, invariant.condition, observed, NotEvaluatedReason.EXPRESSION_FAILED, exception.message,
            )
        }
    }

    /**
     * Evaluates one condition.
     *
     * References the specification wrote are substituted first, so what gets compiled - and what the verdict
     * reports - is the condition with real numbers in it. A verdict that shows `dbStock == 10 - successQuantity`
     * is something an operator can check; one that shows a placeholder is not.
     */
    private fun evaluate(
        invariant: Invariant,
        bindings: Map<String, Any>,
        observed: Map<String, ObservedValue>,
        staticBindings: Map<String, String>,
    ): InvariantVerdict {
        val identifiers = bindings.keys
        val condition = references.resolve(invariant.condition, staticBindings)
        val holds = expressions.compile(condition, identifiers).evaluateBoolean(bindings)
        if (holds) return verdict(invariant, condition, InvariantOutcome.PASSED, observed)

        // A reviewer already decided these cases are correct behaviour, so they are not violations.
        val accepted = invariant.exceptions.firstOrNull { exception ->
            runCatching {
                val resolved = references.resolve(exception.condition, staticBindings)
                expressions.compile(resolved, identifiers).evaluateBoolean(bindings)
            }.getOrDefault(false)
        }
        return if (accepted == null) {
            verdict(invariant, condition, InvariantOutcome.VIOLATED, observed)
        } else {
            verdict(invariant, condition, InvariantOutcome.PASSED, observed).copy(
                appliedException = accepted.description,
                detail = "Accepted by an approved exception: ${accepted.description}",
            )
        }
    }

    private fun verdict(
        invariant: Invariant,
        condition: String,
        outcome: InvariantOutcome,
        observed: Map<String, ObservedValue>,
    ): InvariantVerdict = InvariantVerdict(
        invariantId = invariant.id,
        description = invariant.description,
        outcome = outcome,
        condition = condition,
        observedValues = observed.mapValues { (_, value) -> value.display },
    )

    private fun notEvaluated(
        invariant: Invariant,
        condition: String,
        observed: Map<String, ObservedValue>,
        reason: NotEvaluatedReason,
        detail: String?,
    ): InvariantVerdict = verdict(invariant, condition, InvariantOutcome.NOT_EVALUATED, observed)
        .copy(notEvaluatedReason = reason, detail = detail)

    /**
     * A trial only passes when nothing was violated and nothing went unjudged.
     *
     * Treating an unjudged invariant as a pass is how a reliability tool starts lying: it would report success for
     * a property it never actually checked.
     */
    private fun outcomeOf(verdicts: List<InvariantVerdict>): TrialOutcome = when {
        verdicts.any { it.outcome == InvariantOutcome.VIOLATED } -> TrialOutcome.VIOLATED
        verdicts.any { it.outcome == InvariantOutcome.NOT_EVALUATED } -> TrialOutcome.INCONCLUSIVE
        else -> TrialOutcome.PASSED
    }
}

/**
 * One observed value, or the record that it could not be read.
 *
 * Absence is carried explicitly rather than as a null value, because "we read zero" and "we could not read" lead to
 * different verdicts and collapsing them would silently turn an unjudgeable run into a passing one.
 */
data class ObservedValue(
    val present: Boolean,
    val value: Any?,
    val display: String,
) {
    companion object {
        fun of(value: Any): ObservedValue = ObservedValue(true, value, value.toString())
        fun missing(reason: String): ObservedValue = ObservedValue(false, null, "not observed ($reason)")
    }
}
