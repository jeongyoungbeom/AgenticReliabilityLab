package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.RecordedResponse
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Reads one observation's value out of the responses a run collected.
 *
 * This is deliberately not CEL. CEL judges invariants over named values; getting the value out of a response body
 * is a different job with different failure modes, and keeping them apart means a path that does not exist is
 * reported as "we could not read this" instead of turning into a verdict.
 *
 * The grammar is small on purpose: an optional aggregate around a dotted path, where `[*]` flattens an array.
 * Anything richer would let a model write an extraction a reviewer cannot check on an approval screen.
 */
@Component
@Suppress("TooManyFunctions") // The deliberately small extraction grammar is easier to audit in one component.
class ResponsePathEvaluator(
    private val objectMapper: ObjectMapper,
) {
    /** The scope for an observation that reads the responses a workload step captured. */
    fun responsesScope(responses: Map<String, List<RecordedResponse>>): Map<String, Any?> =
        responses.mapValues { (_, list) -> list.map(::responseValue) }

    /** The scope for an observation that makes its own read call. */
    fun responseScope(response: RecordedResponse): Map<String, Any?> = mapOf("response" to responseValue(response))

    fun evaluate(expression: String, scope: Map<String, Any?>): Any {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) throw ObservationExpressionException("Observation expression is empty")
        val call = AGGREGATE.matchEntire(trimmed)
            ?: return single(walk(trimmed, scope), trimmed)
        return aggregate(call.groupValues[1], walk(call.groupValues[2].trim(), scope), trimmed)
    }

    /**
     * The body is parsed only if an expression actually reaches into it.
     *
     * A failed request often answers with an error page rather than JSON. Parsing eagerly would make that one
     * response poison every other observation in the same trial, including the ones that only read status codes.
     */
    private fun responseValue(response: RecordedResponse): Map<String, Any?> = mapOf(
        "status" to response.statusCode.toLong(),
        "durationMs" to response.durationMs,
        "delivered" to response.delivered,
        "body" to lazy { response.body.takeIf(String::isNotBlank)?.let(::readBody) },
    )

    @Suppress("TooGenericExceptionCaught") // A body we cannot parse is simply a value we cannot read.
    private fun readBody(body: String): Any? = try {
        objectMapper.readValue(body, Any::class.java)
    } catch (exception: Exception) {
        throw ObservationExpressionException(
            "Response body is not valid JSON: ${exception.javaClass.simpleName}",
            exception,
        )
    }

    /**
     * Walks a dotted path, flattening at every `[*]`.
     *
     * A field that is absent drops out of the result instead of failing. A missing element genuinely means zero
     * of that thing - an order with no failed items - and treating it as an error would make a passing run
     * unjudgeable. The first segment is the exception: if the named collection itself does not exist, the
     * specification is reading something the run never produced, and that is an error worth reporting.
     */
    private fun walk(path: String, scope: Map<String, Any?>): List<Any?> {
        var values: List<Any?> = listOf(scope)
        path.split('.').forEachIndexed { index, token ->
            val wildcard = token.endsWith(WILDCARD)
            val name = if (wildcard) token.dropLast(WILDCARD.length) else token
            if (name.isBlank()) throw ObservationExpressionException("Path '$path' has an empty segment")
            if (index == 0 && !scope.containsKey(name)) {
                throw ObservationExpressionException("'$name' is not available to this observation")
            }
            values = values.mapNotNull { container -> force(child(container, name)) }
            if (wildcard) values = values.flatMap { value -> elements(value, name) }
        }
        return values
    }

    private fun child(container: Any?, name: String): Any? = (container as? Map<*, *>)?.get(name)

    /** Resolves a value that was only parsed on demand, so a parse failure surfaces as this observation's failure. */
    private fun force(value: Any?): Any? = if (value is Lazy<*>) value.value else value

    private fun elements(value: Any?, name: String): List<Any?> = value as? List<Any?>
        ?: throw ObservationExpressionException("'$name' is not an array, so '[*]' cannot be applied to it")

    private fun single(values: List<Any?>, expression: String): Any = when (values.size) {
        0 -> throw ObservationExpressionException("Expression '$expression' matched nothing")
        1 -> normalise(values.first(), expression)
        else -> values.map { value -> normalise(value, expression) }
    }

    /**
     * Applies an aggregate.
     *
     * `sum` and `count` over nothing are 0, because "no order succeeded" is a real and expected outcome.
     * `max`, `min` and `avg` over nothing have no answer, so they fail rather than invent one.
     */
    private fun aggregate(function: String, values: List<Any?>, expression: String): Any {
        val numbers by lazy { values.map { value -> number(value, expression) } }
        return when (function) {
            "count" -> values.size.toLong()
            "sum" -> if (values.isEmpty()) 0L else total(numbers)
            "max" -> numbers.maxOrNull() ?: emptyAggregate(function, expression)
            "min" -> numbers.minOrNull() ?: emptyAggregate(function, expression)
            "avg" -> if (numbers.isEmpty()) emptyAggregate(function, expression) else numbers.average()
            else -> throw ObservationExpressionException("Unknown function '$function' in '$expression'")
        }
    }

    private fun emptyAggregate(function: String, expression: String): Nothing =
        throw ObservationExpressionException("'$function' in '$expression' has no values to work with")

    /** Whole numbers stay whole. A count that reads `10.0` would not compare equal to a threshold of `10`. */
    private fun total(numbers: List<Double>): Any {
        val sum = numbers.sum()
        return if (numbers.all { it == Math.floor(it) }) sum.toLong() else sum
    }

    private fun number(value: Any?, expression: String): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
            ?: throw ObservationExpressionException("'$value' in '$expression' is not a number")
        else -> throw ObservationExpressionException("Expression '$expression' aggregates a non-numeric value")
    }

    /** CEL compares a Java `Long` to an integer literal; a Jackson `Integer` would not compare equal. */
    private fun normalise(value: Any?, expression: String): Any = when (value) {
        null -> throw ObservationExpressionException("Expression '$expression' read a null value")
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Float -> value.toDouble()
        else -> value
    }

    private companion object {
        val AGGREGATE = Regex("([a-zA-Z]+)\\((.*)\\)", RegexOption.DOT_MATCHES_ALL)
        const val WILDCARD = "[*]"
    }
}

/** The value an observation names could not be read. Not a violation - the invariant becomes unjudgeable. */
class ObservationExpressionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
