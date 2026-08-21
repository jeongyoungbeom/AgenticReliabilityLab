package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.ExecutionPolicy
import com.project.agenticreliabilitylab.testspec.domain.Invariant
import com.project.agenticreliabilitylab.testspec.domain.InvariantException
import com.project.agenticreliabilitylab.testspec.domain.InvariantOutcome
import com.project.agenticreliabilitylab.testspec.domain.NotEvaluatedReason
import com.project.agenticreliabilitylab.testspec.domain.ObservedSpan
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import com.project.agenticreliabilitylab.testspec.domain.UnmetRequirement
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests pin down what the engine must never do.
 *
 * The dangerous failure for a reliability tool is not a missed bug - it is reporting a pass for something it never
 * checked. Most of what follows guards that boundary.
 */
class InvariantEvaluatorTests {
    private val evaluator = InvariantEvaluator(SpecExpressionEnvironment(), SpecReferenceResolver(ObjectMapper()))

    @Test
    fun `passes when every invariant holds`() {
        val result = evaluator.judgeTrial(
            specification(invariant("stock-never-negative", "dbStock >= 0")),
            1,
            mapOf("dbStock" to ObservedValue.of(3L)),
        )

        assertEquals(TrialOutcome.PASSED, result.outcome)
        assertEquals(InvariantOutcome.PASSED, result.verdicts.single().outcome)
    }

    @Test
    fun `records the condition and observed values behind a violation`() {
        val result = evaluator.judgeTrial(
            specification(invariant("stock-never-negative", "dbStock >= 0")),
            1,
            mapOf("dbStock" to ObservedValue.of(-3L)),
        )

        val verdict = result.verdicts.single()
        assertEquals(InvariantOutcome.VIOLATED, verdict.outcome)
        assertEquals("dbStock >= 0", verdict.condition)
        assertEquals("-3", verdict.observedValues["dbStock"])
    }

    @Test
    fun `an unobserved value makes the invariant unjudged rather than violated`() {
        val result = evaluator.judgeTrial(
            specification(invariant("no-dangling-hold", "redisHold == 0")),
            1,
            mapOf("redisHold" to ObservedValue.missing("/state is not available")),
        )

        val verdict = result.verdicts.single()
        assertEquals(InvariantOutcome.NOT_EVALUATED, verdict.outcome)
        assertEquals(NotEvaluatedReason.OBSERVATION_MISSING, verdict.notEvaluatedReason)
        assertEquals(TrialOutcome.INCONCLUSIVE, result.outcome)
    }

    @Test
    fun `an unobserved value does not stop the other invariants from being judged`() {
        val result = evaluator.judgeTrial(
            specification(
                invariant("stock-never-negative", "dbStock >= 0"),
                invariant("no-dangling-hold", "redisHold == 0"),
            ),
            1,
            mapOf(
                "dbStock" to ObservedValue.of(-3L),
                "redisHold" to ObservedValue.missing("/state is not available"),
            ),
        )

        assertEquals(InvariantOutcome.VIOLATED, result.verdicts[0].outcome)
        assertEquals(InvariantOutcome.NOT_EVALUATED, result.verdicts[1].outcome)
        assertEquals(TrialOutcome.VIOLATED, result.outcome)
    }

    @Test
    fun `an unrelated missing observation does not disguise an expression failure`() {
        val result = evaluator.judgeTrial(
            specification(invariant("stock-never-negative", "dbStock >= 0")),
            1,
            mapOf(
                "dbStock" to ObservedValue.of("not-a-number"),
                "redisHold" to ObservedValue.missing("harness is unavailable"),
            ),
        )

        val verdict = result.verdicts.single()
        assertEquals(InvariantOutcome.NOT_EVALUATED, verdict.outcome)
        assertEquals(NotEvaluatedReason.EXPRESSION_FAILED, verdict.notEvaluatedReason)
    }

    @Test
    fun `an unmet requirement leaves the dependent invariant unjudged`() {
        val result = evaluator.judgeTrial(
            specification(
                invariant("converged", "settled"),
                invariant("settlement-matches", "settlementTotal == orderTotal", requires = "converged"),
            ),
            1,
            mapOf(
                "settled" to ObservedValue.of(false),
                "settlementTotal" to ObservedValue.of(700L),
                "orderTotal" to ObservedValue.of(700L),
            ),
        )

        val dependent = result.verdicts[1]
        assertEquals(InvariantOutcome.NOT_EVALUATED, dependent.outcome)
        assertEquals(NotEvaluatedReason.REQUIREMENT_UNMET, dependent.notEvaluatedReason)
    }

    @Test
    fun `an approved exception turns a violation into a pass and says which one`() {
        val result = evaluator.judgeTrial(
            specification(
                invariant(
                    "same-order-returned",
                    "firstOrderId == secondOrderId",
                    exceptions = listOf(
                        InvariantException(
                            condition = "secondStatusCode == 409",
                            description = "처리 중인 같은 키에 409를 주는 것도 규약상 허용된다",
                            evidence = null,
                            approvedBy = "jybeomss",
                        ),
                    ),
                ),
            ),
            1,
            mapOf(
                "firstOrderId" to ObservedValue.of("a"),
                "secondOrderId" to ObservedValue.of("b"),
                "secondStatusCode" to ObservedValue.of(409L),
            ),
        )

        val verdict = result.verdicts.single()
        assertEquals(InvariantOutcome.PASSED, verdict.outcome)
        assertTrue(verdict.appliedException.orEmpty().contains("409"))
    }

    @Test
    fun `an exception that does not apply leaves the violation standing`() {
        val result = evaluator.judgeTrial(
            specification(
                invariant(
                    "same-order-returned",
                    "firstOrderId == secondOrderId",
                    exceptions = listOf(
                        InvariantException("secondStatusCode == 409", "409 is allowed", null, "jybeomss"),
                    ),
                ),
            ),
            1,
            mapOf(
                "firstOrderId" to ObservedValue.of("a"),
                "secondOrderId" to ObservedValue.of("b"),
                "secondStatusCode" to ObservedValue.of(200L),
            ),
        )

        val verdict = result.verdicts.single()
        assertEquals(InvariantOutcome.VIOLATED, verdict.outcome)
        assertNull(verdict.appliedException)
    }

    @Test
    fun `one violating trial fails the specification and the count is kept`() {
        val specification = specification(invariant("stock-never-negative", "dbStock >= 0"))
        val trials = listOf(
            evaluator.judgeTrial(specification, 1, mapOf("dbStock" to ObservedValue.of(3L))),
            evaluator.judgeTrial(specification, 2, mapOf("dbStock" to ObservedValue.of(-1L))),
            evaluator.judgeTrial(specification, 3, mapOf("dbStock" to ObservedValue.of(0L))),
        )

        val result = evaluator.combine(specification, trials)

        assertEquals(TrialOutcome.VIOLATED, result.outcome)
        assertEquals(3, result.trialsRun)
        assertEquals(1, result.trialsViolated)
        assertTrue(result.reason.contains("3 trial(s) run"))
    }

    @Test
    fun `an unjudged trial makes the specification inconclusive rather than passed`() {
        val specification = specification(invariant("no-dangling-hold", "redisHold == 0"))
        val trials = listOf(
            evaluator.judgeTrial(specification, 1, mapOf("redisHold" to ObservedValue.of(0L))),
            evaluator.judgeTrial(specification, 2, mapOf("redisHold" to ObservedValue.missing("no /state"))),
        )

        val result = evaluator.combine(specification, trials)

        assertEquals(TrialOutcome.INCONCLUSIVE, result.outcome)
        assertEquals(1, result.trialsInconclusive)
    }

    @Test
    fun `judges a condition that refers to a workload setting and reports the resolved form`() {
        val specification = specification(
            invariant(
                "all-requests-accounted",
                "successQuantity + failedItemCount == {{workload.orders.requestCount}}",
            ),
        ).copy(
            workload = listOf(
                WorkloadStep(
                    kind = WorkloadStepKind.CALL,
                    name = "orders",
                    call = SpecHttpCall("POST", "/orders", null, emptyMap(), null),
                    requestCount = 10,
                    concurrency = 10,
                ),
            ),
        )

        val result = evaluator.judgeTrial(
            specification,
            1,
            mapOf("successQuantity" to ObservedValue.of(7L), "failedItemCount" to ObservedValue.of(3L)),
        )

        val verdict = result.verdicts.single()
        assertEquals(InvariantOutcome.PASSED, verdict.outcome)
        assertEquals("successQuantity + failedItemCount == 10", verdict.condition)
    }

    /**
     * "The specification is wrong" and "the evidence is thin" send an operator to opposite places. A time-axis
     * function that refused must not be reported as a broken expression.
     */
    @Test
    fun `separates an unjudgeable observation from a broken expression`() {
        val result = evaluator.judgeTrial(
            specification(invariant("no-overlap", "noOverlap(reserveSpans, deductSpans)")),
            1,
            mapOf(
                "reserveSpans" to ObservedValue.of(spans("t1" to 100L)),
                "deductSpans" to ObservedValue.of(spans("t1" to 200L)),
            ),
        )

        val verdict = result.verdicts.single()
        assertEquals(InvariantOutcome.NOT_EVALUATED, verdict.outcome)
        assertEquals(NotEvaluatedReason.OBSERVATION_INSUFFICIENT, verdict.notEvaluatedReason)
        assertTrue(verdict.detail.orEmpty().contains("nothing could interleave"))
    }

    @Test
    fun `still reports a genuinely broken expression as an expression failure`() {
        val result = evaluator.judgeTrial(
            specification(invariant("bad-condition", "dbStock >= ")),
            1,
            mapOf("dbStock" to ObservedValue.of(3L)),
        )

        val verdict = result.verdicts.single()
        assertEquals(NotEvaluatedReason.EXPRESSION_FAILED, verdict.notEvaluatedReason)
    }

    /**
     * How much evidence a judgement rested on is what truncation destroys first, and nothing else in the record
     * can recover it.
     */
    @Test
    fun `keeps the span and trace counts a judgement rested on`() {
        val many = spans(*Array(12) { index -> "t${index % 3}" to index * 10L })

        val result = evaluator.judgeTrial(
            specification(invariant("counted", "traceCount(reserveSpans) == 3")),
            1,
            mapOf("reserveSpans" to ObservedValue.of(many)),
        )

        val shown = result.verdicts.single().observedValues.getValue("reserveSpans")
        assertTrue(shown.contains("12 spans across 3 traces"), shown)
        assertTrue(shown.contains("..."), shown)
    }

    private fun spans(vararg starts: Pair<String, Long>): List<Map<String, Any>> = starts.map { (trace, start) ->
        ObservedSpan(traceId = trace, name = "inventory.reserve", startMs = start, endMs = start + 5L).asBinding()
    }

    private fun invariant(
        id: String,
        condition: String,
        requires: String? = null,
        exceptions: List<InvariantException> = emptyList(),
    ) = Invariant(
        id = id,
        description = id,
        condition = condition,
        requires = requires,
        unmet = UnmetRequirement.NOT_EVALUATED,
        exceptions = exceptions,
        threshold = null,
    )

    private fun specification(vararg invariants: Invariant) = TestSpecification(
        id = UUID.randomUUID(),
        specKey = "spec-under-test",
        version = 1,
        title = "spec under test",
        category = SpecCategory.CONCURRENCY,
        risk = SpecRisk.MODERATE,
        source = SpecSource.MODEL_PROPOSED,
        targetSystemId = "pilot-target",
        profileVersionId = UUID.randomUUID(),
        evidence = emptyList(),
        setup = emptyList(),
        workload = emptyList(),
        observations = emptyList(),
        invariants = invariants.toList(),
        policy = ExecutionPolicy(
            trials = 1,
            aggregation = TrialAggregation.ANY_VIOLATION_FAILS,
            stopPolicy = TrialStopPolicy.STOP_ON_FIRST_VIOLATION,
            cleanupTiming = CleanupTiming.AFTER_ALL,
            trialInterval = Duration.ZERO,
        ),
        cleanup = CleanupMethod.ENVIRONMENT_RESET,
    )
}
