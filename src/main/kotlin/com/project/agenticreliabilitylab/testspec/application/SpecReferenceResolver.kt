package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Fills in the `{{...}}` references a specification writes.
 *
 * An unresolved reference is an error rather than an empty string. A blank product id would still produce a
 * well-formed request, the Target would answer it, and the run would report a verdict about something the
 * specification never meant to test - a wrong answer delivered confidently.
 */
@Component
class SpecReferenceResolver(
    private val objectMapper: ObjectMapper,
) {
    /** Substitutes every reference, or fails naming the first one that has no value yet. */
    fun resolve(text: String, bindings: Map<String, String>): String = PLACEHOLDER.replace(text) { match ->
        val name = match.groupValues[1].trim()
        bindings[name] ?: throw SpecExecutionException("Reference '{{$name}}' has no value at this point")
    }

    fun resolveAll(values: Map<String, String>, bindings: Map<String, String>): Map<String, String> =
        values.mapValues { (_, value) -> resolve(value, bindings) }

    /** Substitutes what it can and leaves the rest in place, so a later check can report every problem at once. */
    fun resolveOrKeep(text: String, bindings: Map<String, String>): String = PLACEHOLDER.replace(text) { match ->
        val name = match.groupValues[1].trim()
        bindings[name] ?: match.value
    }

    /** The references a text uses that the given bindings cannot fill. Used to reject a specification early. */
    fun unresolved(text: String, bindings: Map<String, String>): List<String> =
        PLACEHOLDER.findAll(text)
            .map { match -> match.groupValues[1].trim() }
            .filterNot { name -> name in bindings }
            .distinct()
            .toList()

    /**
     * The bindings that are known before anything runs.
     *
     * Invariant conditions may only use these, because a condition is compiled and reviewed at approval time.
     * Values that exist only during a run - a captured id, the request number - deliberately are not here.
     */
    fun staticBindings(specification: TestSpecification): Map<String, String> = buildMap {
        put("policy.trials", specification.policy.trials.toString())
        specification.workload.filter { it.kind == WorkloadStepKind.CALL }.forEach { step ->
            put("workload.${step.name}.requestCount", step.requestCount.toString())
            put("workload.${step.name}.concurrency", step.concurrency.toString())
        }
        specification.setup.forEach { step ->
            putAll(literalBodyFields("setup.${step.name}", step.call.bodyJson))
        }
    }

    /**
     * Top-level scalar fields of a request body, flattened under [prefix].
     *
     * This is what lets an invariant say "the stock we set up minus the successes" without repeating the number,
     * which is the difference between a threshold a reviewer can trace and one someone invented twice.
     */
    fun bodyFields(prefix: String, bodyJson: String?): Map<String, String> {
        val root = bodyJson?.let(::readObject) ?: return emptyMap()
        return buildMap {
            root.propertyNames().forEach { name ->
                val value = root.path(name)
                if (value.isObject || value.isArray || value.isNull) return@forEach
                put("$prefix.$name", value.asString())
            }
        }
    }

    /** Body fields whose value is itself a reference are left out: they are not known until the run fills them. */
    private fun literalBodyFields(prefix: String, bodyJson: String?): Map<String, String> =
        bodyFields(prefix, bodyJson).filterValues { value -> !PLACEHOLDER.containsMatchIn(value) }

    @Suppress("TooGenericExceptionCaught") // Any read failure means the same thing: this body is unusable.
    private fun readObject(bodyJson: String) = try {
        objectMapper.readTree(bodyJson).takeIf { it.isObject }
    } catch (exception: Exception) {
        throw SpecExecutionException(
            "Request body is not valid JSON: ${exception.javaClass.simpleName}",
            exception,
        )
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{([^}]+)}}")
    }
}
