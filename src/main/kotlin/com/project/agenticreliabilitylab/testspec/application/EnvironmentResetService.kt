package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.ResetCheck
import com.project.agenticreliabilitylab.testspec.domain.ResetOutcome
import com.project.agenticreliabilitylab.testspec.domain.ResetPlan
import com.project.agenticreliabilitylab.testspec.domain.ResetVerification
import org.springframework.stereotype.Component

/**
 * Puts the environment back and then checks that it actually went back.
 *
 * The check is the point. Calling a reset hook and assuming it worked would hand the next run an environment
 * full of this run's leftovers, and that run's verdict would describe our own residue rather than the Target.
 * When verification fails this says so plainly, so the caller can block further runs instead of continuing.
 */
@Component
class EnvironmentResetService(
    private val caller: SpecHttpCaller,
    private val values: SpecValueReader,
    private val expressions: SpecExpressionEnvironment,
) {
    @Suppress("ReturnCount") // Nothing to undo, the hook failing and the checks failing are different outcomes.
    fun reset(plan: ResetPlan, target: RegisteredTarget, runId: String): ResetOutcome {
        if (plan.method == CleanupMethod.NOT_REQUIRED) {
            return ResetOutcome(performed = false, verified = true, checks = emptyList())
        }
        val hook = plan.hook
            ?: return ResetOutcome(false, false, emptyList(), "No reset hook is configured for this target")

        val response = caller.send(target, hook, mapOf("runId" to runId), FIRST_REQUEST, runId)
        if (!response.delivered || response.statusCode !in SUCCESS_STATUS) {
            val reason = response.failure ?: "HTTP ${response.statusCode}"
            return ResetOutcome(false, false, emptyList(), "The reset hook did not succeed: $reason")
        }
        if (plan.verifications.isEmpty()) {
            return ResetOutcome(true, false, emptyList(), "The reset was not verified: no checks are declared")
        }

        val checks = plan.verifications.map { verification -> check(verification, target, runId) }
        val unsatisfied = checks.filterNot { it.satisfied }
        return ResetOutcome(
            performed = true,
            verified = unsatisfied.isEmpty(),
            checks = checks,
            failure = unsatisfied.takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { "'${it.id}' saw ${it.observed}" }
                ?.let { "The environment did not return to its baseline: $it" },
        )
    }

    private fun check(
        verification: ResetVerification,
        target: RegisteredTarget,
        runId: String,
    ): ResetCheck {
        val observed = values.read(
            target = target,
            call = verification.call,
            expression = verification.expression,
            readTiming = verification.readTiming,
            bindings = mapOf("runId" to runId),
            runId = runId,
            label = verification.id,
        )
        if (!observed.present) {
            return ResetCheck(verification.id, verification.condition, observed.display, satisfied = false)
        }
        val satisfied = runCatching {
            expressions.compile(verification.condition, setOf(verification.id))
                .evaluateBoolean(mapOf(verification.id to observed.value!!))
        }.getOrDefault(false)
        return ResetCheck(verification.id, verification.condition, observed.display, satisfied)
    }

    private companion object {
        const val FIRST_REQUEST = 1
        val SUCCESS_STATUS = 200..299
    }
}
