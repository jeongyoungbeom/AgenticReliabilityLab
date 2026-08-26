package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.FaultAuditAction
import com.project.agenticreliabilitylab.testspec.domain.FaultAuditEvent
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionOutcome
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionPlan
import com.project.agenticreliabilitylab.testspec.domain.ResetOutcome
import com.project.agenticreliabilitylab.testspec.domain.ResetPlan
import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import com.project.agenticreliabilitylab.testspec.domain.SpecRunOutcome
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import com.project.agenticreliabilitylab.testspec.domain.TrialResult
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Runs one approved specification end to end: trials, judging, cleanup.
 *
 * Cleanup runs from a `finally`, so a specification that forgets to ask for it still gets it. Leaving a Target
 * dirty does not just waste an environment - it makes the *next* run's verdict describe our own leftovers, and
 * a tool that produces a confident wrong verdict is worse than one that produces none.
 */
@Component
class TestSpecRunner(
    private val executor: SpecWorkloadExecutor,
    private val observations: SpecObservationReader,
    private val evaluator: InvariantEvaluator,
    private val reset: EnvironmentResetService,
    private val faultInjection: FaultInjectionService,
) {
    fun run(
        specification: TestSpecification,
        target: RegisteredTarget,
        plan: ResetPlan,
        runId: String,
        observationSources: Map<String, DeclaredObservationSource> = emptyMap(),
        faultInjectionPlan: FaultInjectionPlan? = null,
        credentialSessionId: String? = null,
    ): SpecRunOutcome {
        requireSafeEnvironment(specification, target)
        val trials = mutableListOf<TrialResult>()
        val executions = mutableListOf<TrialExecution>()
        val resets = mutableListOf<ResetOutcome>()
        val cleanup = PendingCleanup()
        var faultsReleased = true

        try {
            val baseline = safeReset(plan, target, runId, credentialSessionId)
            resets.add(baseline)
            if (!baseline.verified) {
                val reason = baseline.failure ?: "Pre-run reset was not verified"
                executions.add(TrialExecution(1, emptyMap(), emptyMap(), emptyList(), false, failure = reason))
                trials.add(evaluator.unrunnable(specification, 1, reason))
            } else {
                runTrials(
                    specification, target, plan, runId, observationSources, faultInjectionPlan,
                    trials, executions, resets, cleanup, credentialSessionId,
                )
            }
        } finally {
            // Faults are released before the environment reset, so the reset's own verification checks see a
            // Target that is no longer under an injected fault rather than one still mid-failure.
            faultsReleased = releasePendingFaults(faultInjectionPlan, target, runId, executions, credentialSessionId)
            if (cleanup.owed) resets.add(safeReset(plan, target, runId, credentialSessionId))
        }

        return SpecRunOutcome(
            runId = runId,
            result = evaluator.combine(specification, trials),
            executions = executions.toList(),
            resets = resets.toList(),
            cleanupVerified = (resets.isEmpty() || resets.last().verified) && faultsReleased,
        )
    }

    @Suppress("LongParameterList") // One trial loop with its accumulators; splitting them would hide the state.
    private fun runTrials(
        specification: TestSpecification,
        target: RegisteredTarget,
        plan: ResetPlan,
        runId: String,
        observationSources: Map<String, DeclaredObservationSource>,
        faultInjectionPlan: FaultInjectionPlan?,
        trials: MutableList<TrialResult>,
        executions: MutableList<TrialExecution>,
        resets: MutableList<ResetOutcome>,
        cleanup: PendingCleanup,
        credentialSessionId: String?,
    ) {
        for (number in 1..specification.policy.trials) {
            val execution = executor.execute(
                specification,
                target,
                runId,
                number,
                faultInjectionPlan,
                credentialSessionId,
            )
            executions.add(execution)
            if (execution.stateChanged) cleanup.owed = true
            trials.add(judge(specification, target, execution, runId, observationSources, credentialSessionId))

            if (specification.policy.cleanupTiming == CleanupTiming.EACH_TRIAL && cleanup.owed) {
                val outcome = safeReset(plan, target, runId, credentialSessionId)
                resets.add(outcome)
                cleanup.owed = !outcome.verified
                if (!outcome.verified) return
            }
            if (shouldStop(specification, trials.last())) return
            pause(specification.policy.trialInterval)
        }
    }

    private fun judge(
        specification: TestSpecification,
        target: RegisteredTarget,
        execution: TrialExecution,
        runId: String,
        observationSources: Map<String, DeclaredObservationSource>,
        credentialSessionId: String?,
    ): TrialResult = if (execution.completed) {
        val observed = observations.read(
            specification,
            target,
            execution,
            runId,
            observationSources,
            credentialSessionId,
        )
        evaluator.judgeTrial(specification, execution.trialNumber, observed)
    } else {
        evaluator.unrunnable(specification, execution.trialNumber, execution.failure ?: "the trial did not complete")
    }

    /** A cleanup that itself fails must not hide the run's result, so its failure becomes part of the record. */
    @Suppress("TooGenericExceptionCaught") // Whatever went wrong, the environment is now in an unknown state.
    private fun safeReset(
        plan: ResetPlan,
        target: RegisteredTarget,
        runId: String,
        credentialSessionId: String?,
    ): ResetOutcome = try {
        reset.reset(plan, target, runId, credentialSessionId)
    } catch (exception: Exception) {
        val reason = exception.message ?: exception.javaClass.simpleName
        ResetOutcome(false, false, emptyList(), "Cleanup failed: $reason")
    }

    /**
     * Releases every fault handle any trial in this run left outstanding.
     *
     * A trial that released its own fault already removed the handle from what it reports here, so this only
     * ever sees what genuinely was not released - by a trial that died mid-way, or one that simply forgot to.
     * When faults are outstanding but no plan is configured, nothing can release them and this reports failure,
     * which folds into [SpecRunOutcome.cleanupVerified] the same way an unverified environment reset does.
     */
    // No pending handle, no configured plan, and the release outcome are three distinct terminal states.
    private fun releasePendingFaults(
        faultInjectionPlan: FaultInjectionPlan?,
        target: RegisteredTarget,
        runId: String,
        executions: MutableList<TrialExecution>,
        credentialSessionId: String?,
    ): Boolean {
        val released = mutableSetOf<String>()
        var allReleased = true
        executions.indices.forEach { index ->
            val execution = executions[index]
            var events = execution.faultEvents
            execution.pendingFaultHandles.distinct().forEach { handle ->
                if (!released.add(handle)) return@forEach
                val injected = events.lastOrNull { it.action == FaultAuditAction.INJECTED && it.faultId == handle }
                val outcome = faultInjectionPlan?.let { plan ->
                    safeRelease(plan, target, runId, handle, credentialSessionId)
                } ?: FaultInjectionOutcome(handle, false, "No fault injection plan is configured")
                allReleased = allReleased && outcome.succeeded
                events = events + FaultAuditEvent(
                    action = if (outcome.succeeded) FaultAuditAction.RELEASED else FaultAuditAction.RELEASE_FAILED,
                    faultId = handle,
                    faultType = injected?.faultType,
                    scope = injected?.scope,
                    ttlMs = injected?.ttlMs,
                    injectionPoint = injected?.injectionPoint,
                    description = "Runner cleanup release for an outstanding fault",
                    succeeded = outcome.succeeded,
                    failure = outcome.failure,
                )
            }
            if (events !== execution.faultEvents) executions[index] = execution.copy(faultEvents = events)
        }
        return allReleased
    }

    /** Whatever went wrong, the fault must be assumed still active - the same conservative stance as [safeReset]. */
    @Suppress("TooGenericExceptionCaught")
    private fun safeRelease(
        plan: FaultInjectionPlan,
        target: RegisteredTarget,
        runId: String,
        handle: String,
        credentialSessionId: String?,
    ): FaultInjectionOutcome = try {
        faultInjection.release(plan, target, runId, handle, credentialSessionId)
    } catch (exception: Exception) {
        FaultInjectionOutcome(handle, false, exception.message ?: exception.javaClass.simpleName)
    }

    /**
     * The last check before anything is sent.
     *
     * The validator already refuses a state-changing specification the Profile does not allow, but this repeats
     * the environment check against the Target itself. A Profile can be edited; which environment a Target is
     * cannot, and a write to production must fail on the fact rather than on a configuration record about it.
     */
    private fun requireSafeEnvironment(specification: TestSpecification, target: RegisteredTarget) {
        val mutating = (
            specification.setup.map { it.call } + specification.workload.mapNotNull { it.call } +
                specification.observations.mapNotNull { it.call }
            )
            .any { it.method.uppercase() !in READ_METHODS }
        if (mutating && target.environment !in WRITABLE_ENVIRONMENTS) {
            throw SpecExecutionException(
                "This specification changes state, which is refused in '${target.environment}'",
            )
        }
    }

    private fun shouldStop(specification: TestSpecification, trial: TrialResult): Boolean =
        specification.policy.stopPolicy == TrialStopPolicy.STOP_ON_FIRST_VIOLATION &&
            trial.outcome == TrialOutcome.VIOLATED

    private fun pause(interval: Duration) {
        if (!interval.isZero) Thread.sleep(interval.toMillis())
    }

    /** Whether the environment still owes a reset. Held in an object so `finally` can see the latest answer. */
    private class PendingCleanup(var owed: Boolean = false)

    private companion object {
        val READ_METHODS = setOf("GET", "HEAD")
        val WRITABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
    }
}
