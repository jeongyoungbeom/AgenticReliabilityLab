package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.domain.Observation
import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import org.springframework.stereotype.Component

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
) {
    fun read(
        specification: TestSpecification,
        target: RegisteredTarget,
        execution: TrialExecution,
        runId: String,
    ): Map<String, ObservedValue> {
        val responseScope = evaluator.responsesScope(execution.responses)
        return specification.observations.associate { observation ->
            observation.id to readOne(observation, target, execution, responseScope, runId)
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
        ObservationSourceKind.DECLARED_SOURCE -> ObservedValue.missing(
            "source '${observation.sourceName}' is not readable by this build",
        )
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
}
