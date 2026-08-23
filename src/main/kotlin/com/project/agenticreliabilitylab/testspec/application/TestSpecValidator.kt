package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.Observation
import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import org.springframework.stereotype.Component

/**
 * The gate every specification passes before it can be stored or run.
 *
 * A specification is data a model wrote, so the question this answers is not "is it sensible" but "can it reach
 * anything the Profile did not allow". A model may describe and propose; it must never be able to widen execution
 * scope by naming an endpoint, an auth profile, an observation source or a fault the Profile never declared.
 *
 * Every expression is compiled here rather than at run time. A specification that misspells an observation is
 * rejected before anything is dispatched, instead of failing after the Target has already been changed.
 */
@Component
@Suppress("TooManyFunctions") // Each helper owns one independent specification validation dimension.
class TestSpecValidator(
    private val expressions: SpecExpressionEnvironment,
    private val references: SpecReferenceResolver,
) {
    fun validate(specification: TestSpecification, capabilities: TargetSpecCapabilities) {
        val violations = buildList {
            addAll(callViolations(specification, capabilities))
            addAll(observationViolations(specification, capabilities))
            addAll(expressionViolations(specification))
            addAll(requirementViolations(specification))
            addAll(limitViolations(specification, capabilities))
            addAll(stepViolations(specification, capabilities))
            addAll(executionBoundaryViolations(specification))
        }
        if (violations.isNotEmpty()) {
            throw SpecValidationException(violations)
        }
    }

    private fun callViolations(
        specification: TestSpecification,
        capabilities: TargetSpecCapabilities,
    ): List<String> = buildList {
        val calls = specification.setup.map { it.call } + specification.workload.mapNotNull(WorkloadStep::call) +
            specification.observations.mapNotNull { it.call }
        calls.forEach { call ->
            SpecRequestPolicy.specificationPathViolation(call.path)?.let(::add)
            val registeredCall = capabilities.matchingCall(call)
            if (registeredCall == null) {
                add("Call '${call.method} ${call.path}' is not registered in the active Profile")
            } else if (registeredCall in capabilities.authProfilesByCall) {
                val expected = capabilities.authProfilesByCall[registeredCall]
                if (call.authProfile != expected) {
                    add("Call '${call.method} ${call.path}' must use auth profile '${expected ?: "none"}'")
                }
            }
            call.authProfile?.let { profile ->
                if (profile !in capabilities.authProfiles) {
                    add("Auth profile '$profile' is not declared in the active Profile")
                }
            }
            call.headers.keys.forEach { name ->
                SpecRequestPolicy.specificationHeaderViolation(name)?.let(::add)
            }
        }
    }

    private fun observationViolations(
        specification: TestSpecification,
        capabilities: TargetSpecCapabilities,
    ): List<String> = buildList {
        specification.observations.forEach { observation ->
            if (observation.sourceKind == ObservationSourceKind.API) {
                val call = observation.call
                when {
                    call == null -> add("API observation '${observation.id}' must declare a read call")
                    call.method.uppercase() !in READ_METHODS ->
                        add("API observation '${observation.id}' must use GET or HEAD")
                }
            }
            if (observation.sourceKind != ObservationSourceKind.DECLARED_SOURCE) return@forEach
            if (observation.call != null) {
                add("Declared-source observation '${observation.id}' must not declare an HTTP call")
            }
            val source = observation.sourceName
            if (source == null || source !in capabilities.observationSources) {
                add("Observation '${observation.id}' reads undeclared source '$source'")
                return@forEach
            }
            if (!capabilities.providesField(source, observation.expression)) {
                add("Source '$source' does not provide field '${observation.expression}'")
            }
        }
        addAll(sharedSourceTimingViolations(specification, capabilities))
        val duplicates = specification.observations.groupBy { it.id }.filterValues { it.size > 1 }.keys
        duplicates.forEach { add("Observation id '$it' is declared more than once") }
    }

    /**
     * Every observation of one declared source must settle on the same schedule.
     *
     * Fields of one source are read together, in one sampling round, so that invariants comparing them are
     * comparing one moment. Two fields of the same source that settle on different schedules would be read at
     * different times, and an invariant relating them would then judge a difference that only ever existed
     * between the two reads. For spans this is the whole game: a reservation list and a deduction list taken
     * seconds apart disagree about which traces exist, and a trace present in one but not the other looks exactly
     * like work that was started and never finished.
     */
    private fun sharedSourceTimingViolations(
        specification: TestSpecification,
        capabilities: TargetSpecCapabilities,
    ): List<String> = specification.observations
        .filter { observation ->
            observation.sourceKind == ObservationSourceKind.DECLARED_SOURCE &&
                observation.sourceName?.let { capabilities.observationSources.containsKey(it) } == true
        }
        .groupBy { observation -> requireNotNull(observation.sourceName) }
        .mapNotNull { (source, observations) ->
            val timings = observations.map { observation ->
                Triple(
                    observation.readTiming.rule,
                    observation.readTiming.maxWait,
                    observation.readTiming.interval,
                )
            }.distinct()
            "Observations of source '$source' must use one shared read timing"
                .takeIf { timings.size > 1 }
        }

    /**
     * Compiles every expression, including the exceptions.
     *
     * Compiling exceptions here matters: an exception that cannot be evaluated would otherwise be silently ignored
     * at judging time, and an operator would never learn that the exception they approved does nothing.
     */
    private fun expressionViolations(specification: TestSpecification): List<String> = buildList {
        val identifiers = specification.observations.map { it.id }.toSet()
        val known = references.staticBindings(specification)
        specification.invariants.forEach { invariant ->
            addAll(referenceViolations(invariant.id, invariant.condition, known))
            compileOrDescribe(references.resolveOrKeep(invariant.condition, known), identifiers)?.let {
                add("Invariant '${invariant.id}': $it")
            }
            invariant.exceptions.forEach { exception ->
                addAll(referenceViolations(invariant.id, exception.condition, known))
                compileOrDescribe(references.resolveOrKeep(exception.condition, known), identifiers)?.let {
                    add("Exception on '${invariant.id}': $it")
                }
            }
        }
        val duplicates = specification.invariants.groupBy { it.id }.filterValues { it.size > 1 }.keys
        duplicates.forEach { add("Invariant id '$it' is declared more than once") }
    }

    /**
     * A condition may only use values that exist before the run.
     *
     * A captured id or a request number is different on every trial, so a condition mentioning one would be a
     * different condition each time it is judged - and an approval that only held for the first trial.
     */
    private fun referenceViolations(
        invariantId: String,
        condition: String,
        known: Map<String, String>,
    ): List<String> = references.unresolved(condition, known).map { name ->
        "Invariant '$invariantId' refers to '$name', which is not known before the run"
    }

    private fun compileOrDescribe(expression: String, identifiers: Set<String>): String? = try {
        expressions.compile(expression, identifiers)
        null
    } catch (exception: SpecExpressionException) {
        exception.message
    }

    /** A requirement is useful only when its verdict already exists and no requirement chain loops back. */
    private fun requirementViolations(specification: TestSpecification): List<String> = buildList {
        val positions = specification.invariants.mapIndexed { index, invariant -> invariant.id to index }.toMap()
        specification.invariants.forEachIndexed { index, invariant ->
            val required = invariant.requires ?: return@forEachIndexed
            val requiredPosition = positions[required]
            when {
                requiredPosition == null ->
                    add("Invariant '${invariant.id}' requires '$required', which is not declared")
                requiredPosition >= index ->
                    add("Invariant '${invariant.id}' requires '$required', which must be an earlier invariant")
            }
        }
        requirementCycle(specification)?.let { cycle ->
            add("Invariant requirement cycle detected: ${cycle.joinToString(" -> ")}")
        }
    }

    private fun requirementCycle(specification: TestSpecification): List<String>? {
        val requirements = specification.invariants.associate { invariant -> invariant.id to invariant.requires }
        requirements.keys.forEach { start ->
            val positions = mutableMapOf<String, Int>()
            val path = mutableListOf<String>()
            var current: String? = start
            while (current != null && current in requirements) {
                positions[current]?.let { repeatedAt -> return path.drop(repeatedAt) + current }
                positions[current] = path.size
                path.add(current)
                current = requirements[current]
            }
        }
        return null
    }

    private fun limitViolations(
        specification: TestSpecification,
        capabilities: TargetSpecCapabilities,
    ): List<String> = buildList {
        specification.workload.filter { it.kind == WorkloadStepKind.CALL }.forEach { step ->
            if (step.concurrency < 1) {
                add("Step '${step.name}' concurrency must be at least 1")
            }
            if (step.requestCount < 1) {
                add("Step '${step.name}' request count must be at least 1")
            }
            if (step.concurrency > capabilities.maxConcurrency) {
                add(
                    "Step '${step.name}' concurrency ${step.concurrency} " +
                        "exceeds the allowed ${capabilities.maxConcurrency}",
                )
            }
            if (step.requestCount > capabilities.maxRequestCount) {
                add(
                    "Step '${step.name}' request count ${step.requestCount} " +
                        "exceeds the allowed ${capabilities.maxRequestCount}",
                )
            }
            if (step.concurrency > step.requestCount) {
                add("Step '${step.name}' cannot run ${step.concurrency} at once out of ${step.requestCount} requests")
            }
        }
        if (specification.policy.trials > capabilities.maxTrials) {
            add("${specification.policy.trials} trials exceed the allowed ${capabilities.maxTrials}")
        }
        if (specification.policy.trials < 1) {
            add("Trials must be at least 1")
        }
    }

    /**
     * A step that changes state, injects a fault or touches infrastructure must be allowed by the environment.
     *
     * Faults and infrastructure actions also need a deadline. Without one, a run that dies mid-way would leave the
     * Target broken with nothing scheduled to undo it.
     */
    private fun stepViolations(
        specification: TestSpecification,
        capabilities: TargetSpecCapabilities,
    ): List<String> = buildList {
        specification.unsupportedSteps().forEach { kind ->
            add("This build cannot execute step kind '$kind'")
        }
        val mutating = (specification.setup.map { it.call } + specification.workload.mapNotNull(WorkloadStep::call))
            .any { it.method.uppercase() !in READ_METHODS }
        if (mutating && !capabilities.stateChangingAllowed) {
            add("This specification changes state, which '${capabilities.environment}' does not allow")
        }
        specification.workload.forEach { step ->
            when (step.kind) {
                WorkloadStepKind.INJECT_FAULT -> {
                    if (step.faultType !in capabilities.supportedFaults) {
                        add("Fault '${step.faultType}' is not supported by this Target")
                    }
                    val ttl = step.faultTtl
                    if (ttl == null) {
                        add("Fault step '${step.name}' must declare a TTL")
                    } else if (ttl > capabilities.maxFaultTtl) {
                        add("Fault step '${step.name}' TTL exceeds the allowed ${capabilities.maxFaultTtl}")
                    }
                }
                WorkloadStepKind.INFRA_ACTION -> {
                    if (step.infraTarget !in capabilities.infrastructureTargets) {
                        add("Infrastructure target '${step.infraTarget}' is not allowed")
                    }
                    if (step.infraMaxHold == null) add("Infrastructure step '${step.name}' must declare a maximum hold")
                }
                else -> Unit
            }
        }
    }

    private fun executionBoundaryViolations(specification: TestSpecification): List<String> = buildList {
        specification.workload.filter { step -> step.kind == WorkloadStepKind.WAIT }.forEach { step ->
            if (step.wait != null && step.wait > MAX_STEP_WAIT) {
                add("Step '${step.name}' wait exceeds ${MAX_STEP_WAIT.toMillis()}ms")
            }
        }
        if (specification.policy.trialInterval > MAX_TRIAL_INTERVAL) {
            add("Trial interval exceeds ${MAX_TRIAL_INTERVAL.toMillis()}ms")
        }
        specification.observations.forEach { observation -> addAll(observationBoundaryViolations(observation)) }
        val mutating = (specification.setup.map { it.call } + specification.workload.mapNotNull(WorkloadStep::call))
            .any { it.method.uppercase() !in READ_METHODS }
        val minimumRisk = when {
            specification.unsupportedSteps().isNotEmpty() -> SpecRisk.DESTRUCTIVE
            mutating -> SpecRisk.MODERATE
            else -> SpecRisk.SAFE
        }
        if (specification.risk.ordinal < minimumRisk.ordinal) {
            add("Risk '${specification.risk}' understates the required '$minimumRisk' approval")
        }
    }

    private fun observationBoundaryViolations(observation: Observation) = buildList {
        if (
            observation.readTiming.rule != StabilityRule.IMMEDIATE &&
            (observation.readTiming.maxWait.isZero || observation.readTiming.interval.isZero)
        ) {
            add("Observation '${observation.id}' settling waits and intervals must be positive")
        }
        if (observation.readTiming.maxWait > MAX_OBSERVATION_WAIT) {
            add("Observation '${observation.id}' max wait exceeds ${MAX_OBSERVATION_WAIT.toMillis()}ms")
        }
        if (observation.readTiming.interval > MAX_OBSERVATION_INTERVAL) {
            add("Observation '${observation.id}' interval exceeds ${MAX_OBSERVATION_INTERVAL.toMillis()}ms")
        }
    }

    private companion object {
        val MAX_STEP_WAIT = java.time.Duration.ofMinutes(5)
        val MAX_TRIAL_INTERVAL = java.time.Duration.ofMinutes(1)
        val MAX_OBSERVATION_WAIT = java.time.Duration.ofMinutes(1)
        val MAX_OBSERVATION_INTERVAL = java.time.Duration.ofSeconds(10)
        val READ_METHODS = setOf("GET", "HEAD")
    }
}

/** Everything wrong with a specification, reported at once so an author fixes it in one pass. */
class SpecValidationException(val violations: List<String>) :
    RuntimeException("Specification rejected: ${violations.joinToString("; ")}")
