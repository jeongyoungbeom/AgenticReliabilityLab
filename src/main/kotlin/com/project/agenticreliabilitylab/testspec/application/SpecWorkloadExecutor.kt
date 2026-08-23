package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionPlan
import com.project.agenticreliabilitylab.testspec.domain.RecordedResponse
import com.project.agenticreliabilitylab.testspec.domain.SetupStep
import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.StepRole
import com.project.agenticreliabilitylab.testspec.domain.TraceScope
import com.project.agenticreliabilitylab.testspec.domain.StepTiming
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Runs one trial of a specification against the Target's own API.
 *
 * Nothing here is test-only: the fixture is created through the same endpoints a real client uses, which is what
 * lets a Target that never adds ARL-specific code be exercised at all. The engine only decides how many requests
 * go out, when, and what is recorded - never what any of it means.
 */
@Component
class SpecWorkloadExecutor(
    private val caller: SpecHttpCaller,
    private val references: SpecReferenceResolver,
    private val evaluator: ResponsePathEvaluator,
    private val faultInjection: FaultInjectionService,
    private val clock: Clock,
) {
    @Suppress("TooGenericExceptionCaught") // Every failure below ends the trial the same way: nothing to judge.
    fun execute(
        specification: TestSpecification,
        target: RegisteredTarget,
        runId: String,
        trialNumber: Int,
        faultInjectionPlan: FaultInjectionPlan? = null,
    ): TrialExecution {
        val state = TrialState(trialNumber)
        state.bindings.putAll(references.staticBindings(specification))
        state.bindings["runId"] = runId
        state.bindings["trialNumber"] = trialNumber.toString()
        return try {
            specification.setup.forEach { step -> runSetupStep(step, target, state, runId) }
            specification.workload.forEach { step ->
                runWorkloadStep(step, target, state, runId, TraceScope.of(runId, trialNumber), faultInjectionPlan)
            }
            state.toExecution(null)
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            state.toExecution(exception.message ?: exception.javaClass.simpleName)
        }
    }

    /**
     * Creates one fixture and remembers what it produced.
     *
     * A setup step that does not succeed ends the trial. Running a workload against a fixture that may not exist
     * would produce observations nobody can interpret, and an uninterpretable run reported as a result is worse
     * than no run at all.
     */
    private fun runSetupStep(step: SetupStep, target: RegisteredTarget, state: TrialState, runId: String) {
        val prefix = "setup.${step.name}"
        val body = step.call.bodyJson?.let { json -> references.resolve(json, state.bindings) }
        state.bindings.putAll(references.bodyFields(prefix, body))
        state.markStateChange(step.call)

        val startedAt = clock.instant()
        val response = caller.send(target, step.call, state.bindings.toMap(), FIRST_REQUEST, runId)
        state.timings.add(StepTiming(step.name, startedAt, clock.instant(), StepRole.SETUP))
        if (!response.delivered || response.statusCode !in SUCCESS_STATUS) {
            throw SpecExecutionException(
                "Setup step '${step.name}' did not succeed: ${response.failure ?: "HTTP ${response.statusCode}"}",
            )
        }

        val scope = evaluator.responseScope(response)
        step.captures.forEach { (name, expression) ->
            state.bindings["$prefix.$name"] = evaluator.evaluate(expression, scope).toString()
        }
    }

    @Suppress("ThrowsCount", "LongParameterList") // Each missing piece names itself; the plan travels per call.
    private fun runWorkloadStep(
        step: WorkloadStep,
        target: RegisteredTarget,
        state: TrialState,
        runId: String,
        trialScope: String,
        faultInjectionPlan: FaultInjectionPlan?,
    ) {
        val startedAt = clock.instant()
        when (step.kind) {
            WorkloadStepKind.CALL -> {
                val call = step.call ?: throw SpecExecutionException("Step '${step.name}' declares no call")
                state.markStateChange(call)
                state.responses[step.captureAs ?: step.name] =
                    runCall(step, call, target, state, runId, trialScope)
            }
            WorkloadStepKind.WAIT -> Thread.sleep(
                (step.wait ?: throw SpecExecutionException("Step '${step.name}' declares no duration")).toMillis(),
            )
            WorkloadStepKind.INJECT_FAULT -> runInjectFault(step, target, state, runId, faultInjectionPlan)
            WorkloadStepKind.RELEASE_FAULT -> runReleaseFault(step, target, state, runId, faultInjectionPlan)
            else -> throw SpecExecutionException("Step kind '${step.kind}' cannot be executed by this build")
        }
        state.timings.add(StepTiming(step.name, startedAt, clock.instant(), StepRole.WORKLOAD))
    }

    /**
     * Injects one fault and remembers its handle.
     *
     * The handle is kept even when the step never gets released explicitly: [TrialState.toExecution] reports
     * every handle still outstanding, and the Runner releases whatever a trial - successful or not - left behind.
     */
    // Same reason as runWorkloadStep: each missing piece names itself, and the plan is per-call, not
    // per-executor.
    @Suppress("ThrowsCount", "LongParameterList")
    private fun runInjectFault(
        step: WorkloadStep,
        target: RegisteredTarget,
        state: TrialState,
        runId: String,
        faultInjectionPlan: FaultInjectionPlan?,
    ) {
        val plan = faultInjectionPlan
            ?: throw SpecExecutionException("Step '${step.name}' injects a fault but none is configured")
        val faultType = step.faultType ?: throw SpecExecutionException("Step '${step.name}' declares no fault type")
        val ttl = step.faultTtl ?: throw SpecExecutionException("Step '${step.name}' declares no TTL")
        state.markMutation()
        val outcome = faultInjection.inject(plan, target, runId, faultType, step.faultScope, ttl.toMillis())
        val faultId = outcome.faultId
        if (!outcome.succeeded || faultId == null) {
            throw SpecExecutionException(
                "Fault step '${step.name}' did not succeed: ${outcome.failure ?: "no faultId returned"}",
            )
        }
        state.bindings["workload.${step.name}.faultId"] = faultId
        state.activeFaultHandles.add(faultId)
    }

    /** Releases one fault by the handle the specification names, and only then forgets it was outstanding. */
    // Same reason as runWorkloadStep: each missing piece names itself, and the plan is per-call, not
    // per-executor.
    @Suppress("ThrowsCount", "LongParameterList")
    private fun runReleaseFault(
        step: WorkloadStep,
        target: RegisteredTarget,
        state: TrialState,
        runId: String,
        faultInjectionPlan: FaultInjectionPlan?,
    ) {
        val plan = faultInjectionPlan
            ?: throw SpecExecutionException("Step '${step.name}' releases a fault but none is configured")
        val handleReference = step.handleReference
            ?: throw SpecExecutionException("Step '${step.name}' declares no handle")
        val faultId = references.resolve(handleReference, state.bindings)
        state.markMutation()
        val outcome = faultInjection.release(plan, target, runId, faultId)
        if (!outcome.succeeded) {
            throw SpecExecutionException("Fault step '${step.name}' did not release: ${outcome.failure}")
        }
        state.activeFaultHandles.remove(faultId)
    }

    /**
     * Sends a step's requests, holding them at a common gate first.
     *
     * The gate matters. A concurrency defect only shows up when requests genuinely overlap, and starting virtual
     * threads one after another spreads them out enough that a real race can go unobserved - which would report
     * a passing verdict for a test that never actually ran concurrently.
     */
    @Suppress("LongParameterList") // The trial scope has to reach the request itself; nothing closer holds it.
    private fun runCall(
        step: WorkloadStep,
        call: SpecHttpCall,
        target: RegisteredTarget,
        state: TrialState,
        runId: String,
        trialScope: String,
    ): List<RecordedResponse> {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(step.requestCount)
        val finished = CountDownLatch(step.requestCount)
        val permits = Semaphore(step.concurrency)
        val bindings = state.bindings.toMap()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val futures = mutableListOf<Future<RecordedResponse>>()
        try {
            futures += (1..step.requestCount).map { number ->
                executor.submit(
                    Callable {
                        try {
                            started.countDown()
                            permits.acquire()
                            try {
                                gate.await()
                                caller.send(
                                    target,
                                    call,
                                    bindings + ("requestNumber" to "$number"),
                                    number,
                                    runId,
                                    trialScope,
                                )
                            } finally {
                                permits.release()
                            }
                        } finally {
                            finished.countDown()
                        }
                    },
                )
            }
            started.await()
            gate.countDown()
            return futures.map { future -> future.awaitResponse(step.name) }
        } finally {
            executor.cancelAndAwait(futures, finished)
        }
    }

    /** Cancellation marks a Future done before its thread exits, so await the executor itself before cleanup starts. */
    @Suppress("SwallowedException") // Interruption is restored after every request thread has actually terminated.
    private fun ExecutorService.cancelAndAwait(
        futures: List<Future<RecordedResponse>>,
        finished: CountDownLatch,
    ) {
        futures.forEach { future -> future.cancel(true) }
        shutdownNow()
        var interrupted = false
        while (finished.count > 0) {
            try {
                finished.await()
            } catch (_: InterruptedException) {
                interrupted = true
                shutdownNow()
            }
        }
        while (!isTerminated) {
            try {
                awaitTermination(TERMINATION_POLL_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                shutdownNow()
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun Future<RecordedResponse>.awaitResponse(stepName: String): RecordedResponse = try {
        get()
    } catch (exception: ExecutionException) {
        val cause = exception.cause
        throw cause as? RuntimeException
            ?: SpecExecutionException("Step '$stepName' failed: ${cause?.javaClass?.simpleName}", cause ?: exception)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw SpecExecutionException("Step '$stepName' was interrupted", exception)
    }

    /** Everything one trial has accumulated so far, including after a failure. */
    private class TrialState(private val trialNumber: Int) {
        private companion object {
            val READ_METHODS = setOf("GET", "HEAD")
        }

        val bindings = linkedMapOf<String, String>()
        val responses = linkedMapOf<String, List<RecordedResponse>>()
        val timings = mutableListOf<StepTiming>()
        val activeFaultHandles = mutableListOf<String>()
        private var stateChanged = false

        /** A request is counted as changing state when it is about to be sent, not when it succeeds. */
        fun markStateChange(call: SpecHttpCall) {
            if (call.method.uppercase() !in READ_METHODS) stateChanged = true
        }

        /** For state changes that are not one HTTP call the caller controls, such as a fault inject or release. */
        fun markMutation() {
            stateChanged = true
        }

        fun toExecution(failure: String?) = TrialExecution(
            trialNumber = trialNumber,
            bindings = bindings.toMap(),
            responses = responses.toMap(),
            timings = timings.toList(),
            stateChanged = stateChanged,
            pendingFaultHandles = activeFaultHandles.toList(),
            failure = failure,
        )
    }

    private companion object {
        const val TERMINATION_POLL_SECONDS = 1L
        const val FIRST_REQUEST = 1
        val SUCCESS_STATUS = 200..299
    }
}
