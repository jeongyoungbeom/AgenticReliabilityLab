package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationRead
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationRequest
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationSourceClient
import com.project.agenticreliabilitylab.testspec.application.port.SpecExecutionSettings
import com.project.agenticreliabilitylab.testspec.domain.Observation
import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.ObservationWindow
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TraceScope
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Reads the values a trial's invariants will be judged against.
 *
 * A value that could not be read is returned as missing rather than as a default. This is the single most
 * important behaviour in this class: a Target with no state endpoint would otherwise make "no dangling
 * reservation" read as zero and pass, reporting success for a property nobody checked.
 */
@Component
class SpecObservationReader(
    private val values: SpecValueReader,
    private val evaluator: ResponsePathEvaluator,
    private val declaredSources: DeclaredObservationSourceClient,
    private val settings: SpecExecutionSettings,
    private val clock: Clock,
) {
    fun read(
        specification: TestSpecification,
        target: RegisteredTarget,
        execution: TrialExecution,
        runId: String,
        sources: Map<String, DeclaredObservationSource> = emptyMap(),
    ): Map<String, ObservedValue> {
        val responseScope = evaluator.responsesScope(execution.responses)
        val declaredGroups = specification.observations
            .filter { observation -> observation.sourceKind == ObservationSourceKind.DECLARED_SOURCE }
            .groupBy { observation -> observation.sourceName }
        val workloadStart = ObservationWindow.workloadStart(execution.timings)
        val observed = linkedMapOf<String, ObservedValue>()
        specification.observations
            .filter { observation -> observation.sourceKind != ObservationSourceKind.DECLARED_SOURCE }
            .forEach { observation ->
                observed[observation.id] = readOne(observation, target, execution, responseScope, runId)
            }
        declaredGroups.values.forEach { observations ->
            observed.putAll(
                readDeclaredSource(
                    observations,
                    target,
                    runId,
                    sources,
                    workloadStart,
                    TraceScope.of(runId, execution.trialNumber),
                ),
            )
        }
        return specification.observations.associate { observation ->
            observation.id to requireNotNull(observed[observation.id])
        }
    }

    private fun readOne(
        observation: Observation,
        target: RegisteredTarget,
        execution: TrialExecution,
        responseScope: Map<String, Any?>,
        runId: String,
    ): ObservedValue = when (observation.sourceKind) {
        ObservationSourceKind.RESPONSES -> evaluated { evaluator.evaluate(observation.expression, responseScope) }
        ObservationSourceKind.API -> readFromApi(observation, target, execution, runId)
        // Unreachable: read() routes declared sources through their source group. Degrading to a missing
        // value rather than throwing keeps a routing mistake from ending a trial that already changed the Target.
        ObservationSourceKind.DECLARED_SOURCE ->
            ObservedValue.missing("observation '${observation.id}' was not read with its source group")
    }

    @Suppress("ReturnCount", "LongParameterList") // One sampling round needs every part of the trial context.
    private fun readDeclaredSource(
        observations: List<Observation>,
        target: RegisteredTarget,
        runId: String,
        sources: Map<String, DeclaredObservationSource>,
        workloadStart: Instant?,
        trialScope: String,
    ): Map<String, ObservedValue> {
        val sourceName = observations.first().sourceName
            ?: return observations.missing("declared observations have no source name")
        val source = sources[sourceName]
            ?: return observations.missing("source '$sourceName' is not available in the active Profile")
        val timings = observations.map { observation -> observation.readTiming.toKey() }.distinct()
        if (timings.size != 1) {
            return observations.missing("source '$sourceName' must use one shared read timing")
        }
        val timing = observations.first().readTiming
        val required = timing.rule.consecutiveReads()
        val effectiveWait = minOf(timing.maxWait, settings.maxObservationWait)
        val deadline = System.nanoTime() + effectiveWait.toNanos()
        val interval = timing.interval.coerceAtLeast(MINIMUM_INTERVAL)
        val fields = observations.mapTo(linkedSetOf()) { observation -> observation.expression }
        val states = fields.associateWith { SettlingField() }

        var timeout = requestTimeout(required, deadline)
        while (timeout != null) {
            val reads = declaredSources.read(
                DeclaredObservationRequest(
                    target = target,
                    source = source,
                    fields = fields,
                    runId = runId,
                    trialScope = trialScope,
                    timeout = timeout,
                    window = workloadStart?.let { start ->
                        ObservationWindow.spanning(start, clock.instant(), TRACE_WINDOW_MARGIN)
                    },
                ),
            )
            fields.forEach { field -> states.getValue(field).record(reads[field], field) }
            if (required == 1 || states.values.all { state -> state.repeats >= required }) {
                return observations.observedFrom(states, required, effectiveWait)
            }
            val remaining = remainingNanos(deadline)
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(minOf(interval.toNanos(), remaining))
            timeout = requestTimeout(required, deadline)
        }
        return observations.observedFrom(states, required, effectiveWait)
    }

    private fun readFromApi(
        observation: Observation,
        target: RegisteredTarget,
        execution: TrialExecution,
        runId: String,
    ): ObservedValue {
        val call = observation.call
            ?: return ObservedValue.missing("observation '${observation.id}' declares no read call")
        return values.read(
            target = target,
            call = call,
            expression = observation.expression,
            readTiming = observation.readTiming,
            bindings = execution.bindings,
            runId = runId,
            label = observation.id,
        )
    }

    private fun evaluated(read: () -> Any): ObservedValue = try {
        ObservedValue.of(read())
    } catch (exception: ObservationExpressionException) {
        ObservedValue.missing(exception.message ?: "the value could not be read")
    }

    private fun StabilityRule.consecutiveReads(): Int = when (this) {
        StabilityRule.IMMEDIATE -> 1
        StabilityRule.TWO_CONSECUTIVE_EQUAL -> 2
        StabilityRule.THREE_CONSECUTIVE_EQUAL -> REQUIRED_THREE_EQUAL_READS
    }

    private fun requestTimeout(required: Int, deadline: Long): Duration? {
        if (required == 1) return settings.requestTimeout
        val remaining = remainingNanos(deadline)
        return remaining.takeIf { nanos -> nanos > 0 }?.let(Duration::ofNanos)
    }

    private fun remainingNanos(deadline: Long): Long = deadline - System.nanoTime()

    private fun List<Observation>.missing(reason: String): Map<String, ObservedValue> =
        associate { observation -> observation.id to ObservedValue.missing(reason) }

    private fun List<Observation>.observedFrom(
        states: Map<String, SettlingField>,
        required: Int,
        effectiveWait: Duration,
    ): Map<String, ObservedValue> = associate { observation ->
        val state = states.getValue(observation.expression)
        val observed = state.latest?.takeIf { read -> read.present && read.value != null && state.repeats >= required }
        observation.id to if (observed == null) {
            ObservedValue.missing(
                state.lastFailure ?: "value did not settle within ${effectiveWait.toMillis()}ms",
            )
        } else {
            ObservedValue.of(requireNotNull(observed.value))
        }
    }

    private fun ReadTiming.toKey() = ReadTimingKey(
        rule = rule,
        maxWait = maxWait,
        interval = interval,
    )

    private data class ReadTimingKey(
        val rule: StabilityRule,
        val maxWait: Duration,
        val interval: Duration,
    )

    private data class SettlingField(
        var previous: Any? = null,
        var repeats: Int = 0,
        var latest: DeclaredObservationRead? = null,
        var lastFailure: String? = null,
    ) {
        fun record(
            read: DeclaredObservationRead?,
            field: String,
        ) {
            if (read?.present == true && read.value != null) {
                repeats = if (repeats > 0 && read.value == previous) repeats + 1 else 1
                previous = read.value
                latest = read
                lastFailure = null
            } else {
                previous = null
                repeats = 0
                latest = read
                lastFailure = read?.failure ?: "source omitted requested field '$field'"
            }
        }
    }

    private companion object {
        const val REQUIRED_THREE_EQUAL_READS = 3
        val MINIMUM_INTERVAL: Duration = Duration.ofMillis(50)

        /** Slack for clock skew between ARL and whatever collected the Target's spans. */
        val TRACE_WINDOW_MARGIN: Duration = Duration.ofSeconds(5)
    }
}
